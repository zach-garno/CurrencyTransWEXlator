package com.wex.currencytranswexlator.transaction.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a purchase transaction stored in USD.
 *
 * Design decisions:
 * - @Immutable: JPA will never issue an UPDATE for this entity. Enforces
 *   the append-only requirement at the ORM layer as a second defense behind
 *   DB-level INSERT-only grants on the application role.
 * - amountUsd stored as BigDecimal mapped to DECIMAL(19,4). This prevents
 *   IEEE 754 float precision corruption on storage/retrieval. No arithmetic
 *   is performed on this value in application code - the Treasury API handles
 *   conversion math. Rounding to 2dp happens at the display layer only.
 * - id is UUID assigned by the application on creation, not a DB sequence.
 *   This supports idempotency checks before the DB insert.
 * - createdAt is server-assigned at persist time, never client-supplied.
 */
@Entity
@Immutable
@Table(name = "transactions")
@Getter
@NoArgsConstructor
@ToString
public class PurchaseTransaction {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "description", nullable = false, length = 50)
    private String description;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    /**
     * Stored as DECIMAL(19,4) - sufficient precision for USD amounts.
     * BigDecimal used exclusively; no double/float anywhere in the domain.
     */
    @Column(name = "amount_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountUsd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PurchaseTransaction(String description, LocalDate transactionDate, BigDecimal amountUsd) {
        this.id = UUID.randomUUID();
        this.description = description;
        this.transactionDate = transactionDate;
        this.amountUsd = amountUsd;
        this.createdAt = Instant.now();
    }
}
