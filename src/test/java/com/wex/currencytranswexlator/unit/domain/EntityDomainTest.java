package com.wex.currencytranswexlator.unit.domain;

import com.wex.currencytranswexlator.common.idempotency.IdempotencyRecord;
import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit - Domain
 * Entity construction, invariants, and TTL logic. No Spring context.
 */
@DisplayName("Domain Entity Tests")
class EntityDomainTest {

    // ── PurchaseTransaction ───────────────────────────────────────────────────

    @Nested
    @DisplayName("PurchaseTransaction construction")
    class PurchaseTransactionTest {

        @Test
        @DisplayName("constructor assigns a non-null UUID")
        void idIsAssigned() {
            var tx = new PurchaseTransaction("Coffee", LocalDate.now(), new BigDecimal("4.50"));
            assertThat(tx.getId()).isNotNull();
        }

        @Test
        @DisplayName("two transactions have different IDs")
        void idsAreUnique() {
            var tx1 = new PurchaseTransaction("A", LocalDate.now(), new BigDecimal("1.00"));
            var tx2 = new PurchaseTransaction("B", LocalDate.now(), new BigDecimal("2.00"));
            assertThat(tx1.getId()).isNotEqualTo(tx2.getId());
        }

        @Test
        @DisplayName("createdAt is set server-side on construction")
        void createdAtIsSet() {
            var tx = new PurchaseTransaction("Test", LocalDate.now(), new BigDecimal("10.00"));
            assertThat(tx.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("amountUsd is stored with exact BigDecimal precision")
        void amountPrecisionPreserved() {
            BigDecimal amount = new BigDecimal("99.99");
            var tx = new PurchaseTransaction("Test", LocalDate.now(), amount);
            // Stored value should compare equal to original - no floating point corruption
            assertThat(tx.getAmountUsd()).isEqualByComparingTo(amount);
        }

        @Test
        @DisplayName("fields are stored as provided")
        void fieldsStoredCorrectly() {
            var date = LocalDate.of(2026, 3, 15);
            var tx = new PurchaseTransaction("Office supplies", date, new BigDecimal("42.99"));
            assertThat(tx.getDescription()).isEqualTo("Office supplies");
            assertThat(tx.getTransactionDate()).isEqualTo(date);
            assertThat(tx.getAmountUsd()).isEqualByComparingTo(new BigDecimal("42.99"));
        }
    }

    // ── IdempotencyRecord ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("IdempotencyRecord TTL")
    class IdempotencyRecordTest {

        @Test
        @DisplayName("newly created record is not expired")
        void newRecordIsNotExpired() {
            var record = new IdempotencyRecord("key-1", java.util.UUID.randomUUID(), "hash");
            assertThat(record.isExpired()).isFalse();
        }

        @Test
        @DisplayName("TTL is approximately 24 hours from creation")
        void ttlIsApproximately24Hours() {
            var record = new IdempotencyRecord("key-2", java.util.UUID.randomUUID(), "hash");
            var twentyThreeHoursFromNow = java.time.Instant.now().plusSeconds(23 * 3600);
            var twentyFiveHoursFromNow = java.time.Instant.now().plusSeconds(25 * 3600);

            assertThat(record.getExpiresAt()).isAfter(twentyThreeHoursFromNow);
            assertThat(record.getExpiresAt()).isBefore(twentyFiveHoursFromNow);
        }

        @Test
        @DisplayName("record with past expiresAt is expired")
        void expiredRecordDetected() throws Exception {
            // Use reflection to set expiresAt in the past for testing
            var record = new IdempotencyRecord("key-3", java.util.UUID.randomUUID(), "hash");
            var field = IdempotencyRecord.class.getDeclaredField("expiresAt");
            field.setAccessible(true);
            field.set(record, java.time.Instant.now().minusSeconds(1));

            assertThat(record.isExpired()).isTrue();
        }
    }
}
