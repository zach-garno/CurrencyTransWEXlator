package com.wex.currencytranswexlator.common.idempotency;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived record linking a client-supplied idempotency key to a stored
 * transaction. Expires after 24 hours.
 *
 * If a duplicate POST arrives within the TTL window with the same key,
 * the original transactionId is returned without a second DB insert.
 *
 * If the same key arrives with a different payload hash, a 409 is returned.
 */
@Entity
@Table(name = "idempotency_records",
       indexes = @Index(name = "idx_idempotency_key", columnList = "idempotency_key"))
@Getter
@NoArgsConstructor
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    /**
     * SHA-256 hash of the original request payload.
     * Used to detect same-key / different-payload conflicts (409).
     */
    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public IdempotencyRecord(String idempotencyKey, UUID transactionId, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.transactionId = transactionId;
        this.requestHash = requestHash;
        this.expiresAt = Instant.now().plusSeconds(86400); // 24-hour TTL
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
