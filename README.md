# CurrencyTransWEXlator

**WEX Inc. Technical Assessment** - Currency conversion service  
*Zach Garno, Senior System Architect*

A Spring Boot service that stores USD purchase transactions and retrieves them converted to supported currencies using U.S. Treasury exchange-rate data.

---

## Prerequisites

- Docker Desktop 4.x+
- Java 21 (only required for local development and running tests outside Docker)
- Maven 3.9+ (only required for local development and running tests)
---

## Quickstart

```bash
# Clone and start - that's it 
git clone https://github.com/zach-garno/CurrencyTransWEXlator
cd CurrencyTransWEXlator
docker compose up --build

#Note: First startup may take ~30 seconds while exchange-rate data is synchronized from the U.S. Treasury API and Flyway migrations are applied.
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

## Assessment Scope

This solution intentionally focuses on the requirements defined in the WEX assessment while demonstrating production-oriented architectural patterns.

Out of scope:
- Authentication and authorization
- Full ISO 4217 currency normalization
- Distributed caching
- Rate limiting and API throttling
- Event streaming / messaging
- Performance and load testing

**See the Design History Record (DHR) for rationale and future-state considerations.**

*Development Note*

*AI-assisted development tools were used during implementation for scaffolding, documentation formatting, and code generation. Architectural decisions, debugging, validation, and final implementation review were performed manually.*

---

## Test Coverage

The project currently contains 82 automated tests covering:
- Domain rules
- Validation
- Idempotency behavior
- Currency conversion
- Treasury API integration
- Circuit breaker behavior
- API contract validation

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
