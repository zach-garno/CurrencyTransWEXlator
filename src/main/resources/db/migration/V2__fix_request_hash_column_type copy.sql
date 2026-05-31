-- V2__fix_request_hash_column_type.sql
-- Corrects request_hash in idempotency_records from CHAR(64) to VARCHAR(255).
--
-- CHAR(n) in PostgreSQL creates a bpchar (blank-padded char) column.
-- Hibernate maps String fields to varchar, causing schema validation failure
-- on startup. VARCHAR(255) aligns with Hibernate's default String mapping.
--
-- This is a non-destructive ALTER - no data is lost or truncated.
-- SHA-256 hex values are 64 characters; VARCHAR(255) accommodates this
-- and any future hash algorithm changes without another migration.

ALTER TABLE idempotency_records
    ALTER COLUMN request_hash TYPE VARCHAR(255);
