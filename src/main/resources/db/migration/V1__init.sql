-- V1__init.sql
-- Initial schema for CurrencyTransWEXlator
-- Flyway owns all schema changes. JPA is set to validate-only.

-- ─── Transactions ─────────────────────────────────────────────────────────────
-- Append-only. Application role has INSERT and SELECT only (enforced via grants
-- in production; see V2 for role setup).
CREATE TABLE transactions (
    id               UUID        NOT NULL,
    description      VARCHAR(50) NOT NULL,
    transaction_date DATE        NOT NULL,
    amount_usd       DECIMAL(19, 4) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount_usd > 0),
    CONSTRAINT chk_transactions_description_not_blank CHECK (trim(description) <> '')
);

COMMENT ON TABLE transactions IS 'Immutable purchase transaction records. No UPDATE or DELETE permitted.';
COMMENT ON COLUMN transactions.amount_usd IS 'Stored as DECIMAL(19,4). No arithmetic performed in application; display rounding to 2dp at response layer only.';

-- ─── Exchange Rates ───────────────────────────────────────────────────────────
-- Temporal model: multiple records per currency, one per effective_date.
-- Used for 6-month lookback queries on retrieval.
CREATE TABLE exchange_rates (
    id             UUID           NOT NULL,
    currency_code  VARCHAR(100)   NOT NULL,
    effective_date DATE           NOT NULL,
    record_date    DATE           NOT NULL,
    rate           DECIMAL(19, 6) NOT NULL,
    fetched_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT pk_exchange_rates PRIMARY KEY (id),
    CONSTRAINT uq_exchange_rates_currency_effective_date
        UNIQUE (currency_code, effective_date),
    CONSTRAINT chk_exchange_rates_rate_positive CHECK (rate > 0)
);

CREATE INDEX idx_exchange_rates_lookup
    ON exchange_rates (currency_code, effective_date DESC);

COMMENT ON TABLE exchange_rates IS 'Persisted rates from U.S. Treasury Reporting Rates of Exchange API. Temporal model supports 6-month lookback rule.';
COMMENT ON COLUMN exchange_rates.currency_code IS 'Treasury API country-currency convention, e.g. "Canada-Dollar".';
COMMENT ON COLUMN exchange_rates.record_date IS 'Treasury API record_date. Used for delta refresh: fetch WHERE record_date > MAX(record_date) in this table.';

-- ─── Idempotency Records ──────────────────────────────────────────────────────
-- Short-lived (24h TTL). Links X-Idempotency-Key to stored transaction.
CREATE TABLE idempotency_records (
    id              BIGSERIAL     NOT NULL,
    idempotency_key VARCHAR(255)  NOT NULL,
    transaction_id  UUID          NOT NULL,
    request_hash    VARCHAR(255)  NOT NULL,
    expires_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_idempotency_transaction
        FOREIGN KEY (transaction_id) REFERENCES transactions(id)
);

CREATE INDEX idx_idempotency_key ON idempotency_records (idempotency_key);
CREATE INDEX idx_idempotency_expires ON idempotency_records (expires_at);

COMMENT ON TABLE idempotency_records IS '24-hour TTL records for POST /transactions idempotency. Expired records purged by scheduled job.';
