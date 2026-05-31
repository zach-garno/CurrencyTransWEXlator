package com.wex.currencytranswexlator.transaction.service;

import com.wex.currencytranswexlator.common.exception.ConflictException;
import com.wex.currencytranswexlator.common.exception.NotFoundException;
import com.wex.currencytranswexlator.common.idempotency.IdempotencyRecord;
import com.wex.currencytranswexlator.common.idempotency.IdempotencyRecordRepository;
import com.wex.currencytranswexlator.transaction.controller.TransactionRequest;
import com.wex.currencytranswexlator.transaction.controller.TransactionResponse;
import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;
import com.wex.currencytranswexlator.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    /**
     * Stores a purchase transaction with idempotency protection.
     *
     * Idempotency behavior:
     * - Same key, same payload → return original response (idempotent replay)
     * - Same key, different payload → 409 Conflict
     * - New key → store and return 201
     *
     * @param idempotencyKey Client-supplied unique key (X-Idempotency-Key header)
     * @param request        Validated transaction request
     * @param replayed       Output: set to true if this is an idempotent replay
     */
    @Transactional
    public TransactionResponse storeTransaction(String idempotencyKey,
                                                TransactionRequest request,
                                                boolean[] replayed) {
        String requestHash = hashRequest(request);

        Optional<IdempotencyRecord> existing =
            idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            if (record.isExpired()) {
                // Expired records are treated as non-existent
                idempotencyRecordRepository.delete(record);
            } else if (!record.getRequestHash().equals(requestHash)) {
                throw new ConflictException(
                    "Idempotency key '" + idempotencyKey + "' was already used with a different request payload.");
            } else {
                // Valid replay - return original transaction
                replayed[0] = true;
                PurchaseTransaction original = transactionRepository.findById(record.getTransactionId())
                    .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record references missing transaction: " + record.getTransactionId()));
                log.debug("Idempotency replay for key={}", idempotencyKey);
                return TransactionResponse.from(original);
            }
        }

        // New request - store transaction and idempotency record
        PurchaseTransaction transaction = new PurchaseTransaction(
            request.description(),
            request.transactionDate(),
            request.amountUsd()
        );
        transactionRepository.save(transaction);

        IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
            idempotencyKey, transaction.getId(), requestHash);
        idempotencyRecordRepository.save(idempotencyRecord);

        log.info("Transaction stored: id={} idempotencyKey={}", transaction.getId(), idempotencyKey);
        return TransactionResponse.from(transaction);
    }

    public PurchaseTransaction getTransactionOrThrow(UUID id) {
        return transactionRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Transaction not found: " + id));
    }

    /**
     * SHA-256 hash of the request for idempotency conflict detection.
     * Detects same-key / different-payload 409 scenarios.
     */
    private String hashRequest(TransactionRequest request) {
        String payload = request.description() + "|" +
                         request.transactionDate() + "|" +
                         request.amountUsd().toPlainString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
