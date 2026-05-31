package com.wex.currencytranswexlator.exchange.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted exchange rate record from the Treasury Reporting Rates of Exchange API.
 *
 * Design decisions:
 * - Temporal model: multiple records per currency, one per effectiveDate.
 *   This is required by the 6-month lookback rule - we must be able to find
 *   the rate active on or before a given purchase date.
 * - recordDate is the Treasury API's own publication date. Used by the delta
 *   refresh job to request only records newer than MAX(recordDate) in our DB,
 *   avoiding full re-fetches and scraping of amendment timestamps.
 * - rate stored as BigDecimal / DECIMAL(19,6) - Treasury rates can have more
 *   than 4 decimal places (e.g., JPY rates like 151.234500).
 */
@Entity
@Table(
    name = "exchange_rates",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_exchange_rates_currency_effective_date",
        columnNames = {"currency_code", "effective_date"}
    ),
    indexes = {
        @Index(name = "idx_exchange_rates_lookup",
               columnList = "currency_code, effective_date DESC")
    }
)
@Getter
@NoArgsConstructor
@ToString
public class ExchangeRate {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Treasury API country-currency name, e.g. "Canada-Dollar" */
    @Column(name = "currency_code", nullable = false, length = 100)
    private String currencyCode;

    /** The date this rate became effective per the Treasury API */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /**
     * Treasury API record_date - used for delta refresh polling.
     * Not the same as effectiveDate; Treasury can publish record updates
     * with the same effectiveDate but a newer recordDate.
     */
    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    /** Exchange rate relative to USD. DECIMAL(19,6) to handle high-precision rates. */
    @Column(name = "rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal rate;

    /** When this record was fetched and persisted by our application */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    public ExchangeRate(String currencyCode, LocalDate effectiveDate, LocalDate recordDate, BigDecimal rate) {
        this.id = UUID.randomUUID();
        this.currencyCode = currencyCode;
        this.effectiveDate = effectiveDate;
        this.recordDate = recordDate;
        this.rate = rate;
        this.fetchedAt = Instant.now();
    }
}
