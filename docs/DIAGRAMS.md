# CurrencyTransWEXlator - Architecture Diagrams

> Render in GitHub, GitLab, or any Mermaid-capable viewer.
> Place in `/docs/` or use as the repo README skeleton.

---

## 1. Domain-Driven Design - Bounded Contexts & Aggregates

```mermaid
graph TB
    subgraph TC["Transaction Context"]
        direction TB
        PT["PurchaseTransaction\n─────────────────\n+ id: UUID\n+ description: String\n+ transactionDate: LocalDate\n+ amountUsd: BigDecimal\n+ createdAt: Instant"]
        IR["IdempotencyRecord\n─────────────────\n+ key: String\n+ transactionId: UUID\n+ expiresAt: Instant  (24h TTL)"]
        PT -.->|dedup via| IR
    end

    subgraph ERC["Exchange Rate Context"]
        direction TB
        ER["ExchangeRate\n─────────────────\n+ id: UUID\n+ currencyCode: String\n+ country: String\n+ effectiveDate: LocalDate\n+ recordDate: LocalDate\n+ rate: BigDecimal\n+ fetchedAt: Instant"]
        EPI[["ExchangeRateProvider\n(interface)\n─────────────────\ngetRate(code, date)\ngetSupportedCurrencies()"]]
        ER -.->|produced by| EPI
    end

    subgraph PP["Provider Plugin Context"]
        direction LR
        TP["TreasuryProvider\nFULL"]
        CP["CryptoProvider\nSTUB  X-prefix"]
        LP["LoyaltyProvider\nSTUB  L-prefix"]
        CUP["CustomProvider\nSTUB  YAML config"]
        TP & CP & LP & CUP -->|implements| EPI
    end

    subgraph CC["Conversion Context"]
        CS["ConversionService\n─────────────────\n1. fetch transaction\n2. USD pass-through check\n3. find rate <= txDate, >= txDate-6mo\n4. round to 2dp at display layer\n5. attach ratesAsOf"]
        CR["ConversionResponse (read model)\n─────────────────\n+ id, description, transactionDate\n+ amountUsd, exchangeRate\n+ convertedAmount, currencyCode\n+ ratesAsOf"]
        CS -->|produces| CR
    end

    subgraph CCM["Common"]
        CIM[["CurrencyCodeMapper\n(interface - stub)\nISO 4217 -> Treasury name"]]
    end

    TC -->|transaction| CC
    ERC -->|rate| CC
    CCM -.->|future impl| ERC

    style TC fill:#1B3A5C,color:#fff,stroke:#1A5C8A
    style ERC fill:#1A5C8A,color:#fff,stroke:#1B3A5C
    style PP fill:#3D3D3D,color:#fff,stroke:#595959
    style CC fill:#2E6B3E,color:#fff,stroke:#1A5C8A
    style CCM fill:#595959,color:#fff,stroke:#3D3D3D
```

---

## 2. Sequence - Application Startup Rate Pre-Load

```mermaid
sequenceDiagram
    autonumber
    participant APP as Spring Boot App
    participant RJ as RateRefreshJob
    participant DB as PostgreSQL
    participant TAPI as Treasury API

    APP->>RJ: onApplicationReady event
    RJ->>DB: SELECT MAX(recordDate) FROM exchange_rates
    DB-->>RJ: null (first startup) OR last known date

    alt First startup (empty DB)
        RJ->>TAPI: GET /rates?filters=record_date:gte:{today-365d}
        TAPI-->>RJ: all rates for past 365 days
        RJ->>DB: bulk INSERT exchange_rates
        DB-->>RJ: persisted (hundreds of rows)
    else Delta refresh (DB has data)
        RJ->>TAPI: GET /rates?filters=record_date:gte:{lastKnownDate}
        TAPI-->>RJ: only new records since last known
        RJ->>DB: INSERT new records only
        DB-->>RJ: delta persisted
    end

    RJ-->>APP: refresh complete, ratesAsOf = now
    Note over APP: 24-hour background refresh scheduled
    Note over APP: POST /admin/rates/refresh triggers this flow on demand
```

---

## 3. Sequence - Store Transaction (with Idempotency)

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant API as REST Controller
    participant IK as IdempotencyFilter
    participant TS as TransactionService
    participant DB as PostgreSQL

    Client->>API: POST /api/v1/transactions\nX-Idempotency-Key: {uuid}
    API->>IK: check key
    IK->>DB: SELECT idempotency_records WHERE key = ?

    alt Duplicate request (key exists)
        DB-->>IK: existing record
        IK-->>Client: 201 (original response)\nX-Idempotency-Replayed: true
    else New request (key not found)
        DB-->>IK: null
        IK->>TS: validate + store
        TS->>TS: validate fields\ndescription max 50 chars\ndate is valid, not future\namountUsd positive DECIMAL
        TS->>DB: INSERT transactions\n(amountUsd as DECIMAL 19,4)
        DB-->>TS: saved entity
        TS->>DB: INSERT idempotency_records\n(TTL: now + 24h)
        TS-->>API: TransactionResponse
        API-->>Client: 201 Created
    end
```

---

## 4. Sequence - Retrieve and Convert Transaction

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant API as REST Controller
    participant CS as ConversionService
    participant ERS as ExchangeRateService
    participant CB as CircuitBreaker
    participant TAPI as Treasury API
    participant DB as PostgreSQL

    Client->>API: GET /transactions/{id}/convert?currency=Canada-Dollar
    API->>CS: convert(id, "Canada-Dollar")
    CS->>DB: SELECT * FROM transactions WHERE id = ?
    DB-->>CS: PurchaseTransaction (or 404)

    alt currency is USD or not specified
        CS-->>API: ConversionResponse\nexchangeRate=1.0\nconvertedAmount=amountUsd\nratesAsOf=now
        API-->>Client: 200 OK (USD pass-through)
    else currency conversion required
        CS->>ERS: getRate("Canada-Dollar", transactionDate)
        ERS->>DB: SELECT rate WHERE currencyCode = ?\nAND effectiveDate <= txDate\nAND effectiveDate >= txDate - interval 6 months\nORDER BY effectiveDate DESC LIMIT 1

        alt Rate found in local DB
            DB-->>ERS: ExchangeRate record
            ERS-->>CS: rate
        else Cache miss - currency not pre-loaded
            DB-->>ERS: empty
            ERS->>CB: fetch via circuit breaker
            CB->>TAPI: GET /rates?filters=currency:eq:Canada-Dollar\n&record_date:gte:{txDate-6mo}
            alt Treasury API healthy
                TAPI-->>CB: rate records
                CB-->>ERS: rates
                ERS->>DB: INSERT exchange_rates (persist for future)
                DB-->>ERS: saved
                ERS-->>CS: rate
            else Circuit OPEN or API unavailable
                CB-->>ERS: CircuitBreakerOpenException
                ERS-->>CS: ServiceUnavailableException
                CS-->>API: 503 with Retry-After header
                API-->>Client: 503 Service Unavailable
            end
        end

        alt Rate within 6 months found
            CS->>CS: convertedAmount =\nrate.multiply(amountUsd)\nrounded to 2dp HALF_UP\nat display layer only
            CS-->>API: ConversionResponse with ratesAsOf
            API-->>Client: 200 OK
        else No rate within 6-month window
            CS-->>API: RateNotFoundException
            API-->>Client: 422 Unprocessable Entity\n"Purchase cannot be converted\nto the target currency"
        end
    end
```

---

## 5. Deployment - Local (Docker Compose)

```mermaid
graph LR
    DEV["Developer\ncurl / browser"]

    subgraph DC["docker compose up  -  single command"]
        direction TB
        subgraph APP["app  :8080"]
            SB["Spring Boot 3.x / Java 21\nStartup pre-load on ready"]
        end
        subgraph DB["db  :5432"]
            PG["PostgreSQL 16\nFlyway migrations on start"]
        end
        subgraph ADM["adminer  :8081  (dev profile only)"]
            UI["DB inspection UI"]
        end
        APP -->|JDBC env vars| DB
        ADM --> DB
    end

    DEV -->|HTTP :8080| APP
    DEV -->|HTTP :8081| ADM
    APP -->|HTTPS + circuit breaker\n5s timeout| TAPI["Treasury API\nfiscaldata.treasury.gov"]
```

---

## 6. Deployment - Production AWS Reference

```mermaid
graph TB
    CLIENT["Client"] --> ALB

    subgraph AWS["AWS  us-east-1"]
        ALB["Application Load Balancer\n~$20/mo\nhealth: /actuator/health"]

        subgraph ECS["ECS Fargate Cluster"]
            A["Task A\n0.5 vCPU / 1GB\n~$17/mo"]
            B["Task B\n0.5 vCPU / 1GB\n~$17/mo"]
        end

        subgraph DATA["Data"]
            RDS["RDS PostgreSQL 16\nt3.micro\nPOC single-AZ ~$25/mo\nProd Multi-AZ ~$50/mo"]
        end

        subgraph OPS["Operations"]
            SM["Secrets Manager\n~$1/mo"]
            CW["CloudWatch\n~$5/mo"]
        end

        subgraph FUTURE["Scale Threshold Addition"]
            REDIS["ElastiCache Redis\nt3.micro ~$15/mo\nADD WHEN: p95 latency\nexceeds 75% of OLA\nand DB reads are bottleneck\nTTL aligned to 24h refresh\ninvalidated by admin refresh"]
        end

        ALB --> A & B
        A & B -->|JDBC encrypted| RDS
        A & B --> SM
        A & B --> CW
        A & B -.->|future read-through| REDIS
    end

    A & B -->|HTTPS circuit breaker| TAPI["Treasury API"]

    style FUTURE fill:#fff3e0,stroke:#e65100,stroke-dasharray: 5 5,color:#000
    style ECS fill:#e8f4e8,stroke:#2E6B3E,color:#000
    style DATA fill:#e3f2fd,stroke:#1A5C8A,color:#000
```

---

## 7. Provider Plugin Architecture

```mermaid
classDiagram
    class ExchangeRateProvider {
        <<interface>>
        +getRate(currencyCode, purchaseDate) Optional~ExchangeRate~
        +getSupportedCurrencies() Set~String~
        +getProviderName() String
    }

    class CurrencyCodeMapper {
        <<interface - stub>>
        +toTreasuryName(iso4217Code) String
        +fromTreasuryName(treasuryName) String
        +note() ISO 4217 impl deferred - production requirement
    }

    class TreasuryExchangeRateProvider {
        -treasuryClient TreasuryApiClient
        -rateRepository ExchangeRateRepository
        -circuitBreaker CircuitBreaker
        +getRate(code, date) Optional~ExchangeRate~
        +getSupportedCurrencies() Set~String~
    }

    class CryptoExchangeRateProvider {
        <<stub>>
        +note() X-prefix codes X-BTC X-ETH X-SOL
    }

    class LoyaltyPointsProvider {
        <<stub>>
        +note() L-prefix codes L-MILES L-POINTS
    }

    class CustomExchangeProvider {
        <<stub>>
        +note() Static rates via YAML config
    }

    class ProviderRegistry {
        -providers List~ExchangeRateProvider~
        +resolve(currencyCode) ExchangeRateProvider
        +note() ISO4217 to Treasury / X to Crypto / L to Loyalty
    }

    ExchangeRateProvider <|.. TreasuryExchangeRateProvider
    ExchangeRateProvider <|.. CryptoExchangeRateProvider
    ExchangeRateProvider <|.. LoyaltyPointsProvider
    ExchangeRateProvider <|.. CustomExchangeProvider
    ProviderRegistry --> ExchangeRateProvider
    CurrencyCodeMapper ..> TreasuryExchangeRateProvider : future wiring
```

---

## 8. Test Category Architecture

```mermaid
graph TD
    subgraph UNIT["Unit Tests  -  no Spring context  -  fast"]
        UD["domain/\nBigDecimal storage precision\nDate validation and boundary\n6-month window edge cases\nUSD pass-through logic\nConversionResponse assembly\nratesAsOf field behavior"]
        US["security/\nSQL injection in description\nXSS payload handling\nOversized input 51+ chars\nNegative and zero amounts\nFuture transaction dates\nMalformed date strings\nNull required fields"]
    end

    subgraph INT["Integration Tests  -  Testcontainers PostgreSQL + WireMock"]
        IA["api/\nFull HTTP store + retrieve cycle\nIdempotency: first call\nIdempotency: duplicate same payload\nIdempotency: duplicate diff payload 409\nField constraint enforcement\nUSD default pass-through\nError response shapes"]
        IT["treasury/\nWireMock: valid rate returned\nWireMock: rate exists but older than 6mo 422\nWireMock: no rate for currency 422\nWireMock: API timeout circuit opens\nWireMock: 503 circuit behavior\nDelta refresh job execution\nAdmin manual refresh endpoint"]
    end

    subgraph CONTRACT["Contract Tests"]
        IC["openapi/\nOpenAPI 3.0 spec vs implementation\nResponse schema assertions\nStatus code contract verification\nratesAsOf field present on all responses"]
    end

    UNIT --> INT --> CONTRACT

    style UNIT fill:#e8f4e8,stroke:#2E6B3E,color:#000
    style INT fill:#e3f2fd,stroke:#1A5C8A,color:#000
    style CONTRACT fill:#fff3e0,stroke:#e65100,color:#000
```
