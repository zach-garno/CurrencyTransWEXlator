# CurrencyTransWEXlator

**WEX Inc. Technical Assessment** — Currency conversion service  
*Zachary Garno, Senior System Architect*

---

## Quickstart

```bash
# Clone and start - that's it
git clone https://github.com/zach-garno/CurrencyTransWEXlator
cd CurrencyTransWEXlator
docker compose up --build
```

| Service   | URL                                           |
|-----------|-----------------------------------------------|
| API       | http://localhost:8080                         |
| Swagger   | http://localhost:8080/swagger-ui.html         |
| API Docs  | http://localhost:8080/api-docs                |
| Health    | http://localhost:8080/actuator/health         |

**With Adminer DB UI:**
```bash
docker compose --profile dev up
# Adminer: http://localhost:8081  (server: db, user: wex, pass: wex)
```

On first startup, exchange rates are automatically pre-loaded from the U.S. Treasury API.

---

## API Reference

### Store a Purchase Transaction
```http
POST /api/v1/transactions
X-Idempotency-Key: <your-uuid>
Content-Type: application/json

{
  "description": "Office supplies",
  "transactionDate": "2026-03-15",
  "amountUsd": 42.99
}
```

**Response 201:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "description": "Office supplies",
  "transactionDate": "2026-03-15",
  "amountUsd": 42.99,
  "createdAt": "2026-05-27T14:30:00Z"
}
```

---

### Retrieve with Currency Conversion
```http
GET /api/v1/transactions/{id}/convert?currency=Canada-Dollar
```

**Response 200:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "description": "Office supplies",
  "transactionDate": "2026-03-15",
  "amountUsd": 42.99,
  "exchangeRate": 1.3621,
  "convertedAmount": 58.56,
  "currencyCode": "Canada-Dollar",
  "ratesAsOf": "2026-05-27T02:00:00Z"
}
```

**Currency is optional** — omitting it (or passing `USD`) returns the original amount with `exchangeRate: 1.0`.

**Error: No rate within 6 months → 422**
```json
{
  "status": 422,
  "detail": "Purchase cannot be converted to the target currency."
}
```

---

### Other Endpoints
```http
GET  /api/v1/currencies            # List supported currency strings
POST /api/v1/admin/rates/refresh   # Trigger manual rate refresh (async)
GET  /actuator/health              # Health + circuit breaker state
```

---

## Running Tests

```bash
# All tests (requires Docker for Testcontainers)
mvn test

# Unit tests only (no Docker required)
mvn test -pl . -Dtest="com.wex.currencytranswexlator.unit.**"
```

**Test categories:**

| Category           | Location                        | Dependencies       |
|--------------------|---------------------------------|--------------------|
| Unit - Domain      | `unit/domain/`                  | None               |
| Unit - Security    | `unit/security/`                | None               |
| Integration - API  | `integration/api/`              | Docker (Testcontainers) |
| Integration - Treasury | `integration/treasury/`    | Docker + WireMock  |

---

## Architecture

See [`docs/DIAGRAMS.md`](docs/DIAGRAMS.md) for full Mermaid architecture diagrams.  
See [`docs/CurrencyTransWEXlator-DHR.docx`](docs/CurrencyTransWEXlator-DHR.docx) for the full Architecture Decision Record.

**Key decisions:**
- Java 21 (Virtual Threads) + Spring Boot 3.2
- PostgreSQL 16 with Flyway migrations — Flyway owns schema, JPA validates only
- Startup pre-load of exchange rates + 24-hour delta refresh (no Redis at this scale)
- Resilience4j circuit breaker on Treasury API calls
- `ratesAsOf` on all conversion responses for UI staleness disclosure
- Plugin architecture (`ExchangeRateProvider` interface) with Treasury fully implemented; Crypto/Loyalty/Custom as stubs

---

## Design Notes

**Why `X-Idempotency-Key` is required:**  
Physical transactions are unique. Network retries and duplicate submissions must not create duplicate records. This is the standard Stripe/Adyen pattern.

**Why no ISO 4217 mapping:**  
The Treasury API uses its own naming convention. The `CurrencyCodeMapper` interface is defined as a production extension point but not implemented here — it adds domain maintenance burden without value at this scope. See ADR-07 in the DHR.

**Why no Redis:**  
Exchange rates change quarterly. The local DB table *is* the cache. Redis becomes warranted when p95 latency exceeds 75% of OLA and DB reads are confirmed as the bottleneck.

**Why Java over Node:**  
For a greenfield I/O-bound service, Node wins on container weight and cold start. Java 21 + Virtual Threads closes the concurrency gap significantly. For this WEX submission, Spring Boot's production defaults (Actuator, Testcontainers, Resilience4j, Flyway) are directly relevant signal. See ADR-01 in the DHR for the full contested analysis.
