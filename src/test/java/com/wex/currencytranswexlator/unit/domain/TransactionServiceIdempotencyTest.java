package com.wex.currencytranswexlator.unit.domain;

import com.wex.currencytranswexlator.common.exception.ConflictException;
import com.wex.currencytranswexlator.common.idempotency.IdempotencyRecord;
import com.wex.currencytranswexlator.common.idempotency.IdempotencyRecordRepository;
import com.wex.currencytranswexlator.transaction.controller.TransactionRequest;
import com.wex.currencytranswexlator.transaction.controller.TransactionResponse;
import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;
import com.wex.currencytranswexlator.transaction.repository.TransactionRepository;
import com.wex.currencytranswexlator.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit - Domain
 * TransactionService idempotency behavior. No Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService - Idempotency Logic")
class TransactionServiceIdempotencyTest {

    @Mock TransactionRepository transactionRepository;
    @Mock IdempotencyRecordRepository idempotencyRecordRepository;

    TransactionService transactionService;

    @BeforeEach
    void setup() {
        transactionService = new TransactionService(transactionRepository, idempotencyRecordRepository);
    }

    private final TransactionRequest validRequest =
        new TransactionRequest("Test purchase", LocalDate.of(2026, 3, 15), new BigDecimal("50.00"));

    // ── New request (first call) ──────────────────────────────────────────────

    @Nested
    @DisplayName("New idempotency key")
    class NewKey {

        @Test
        @DisplayName("stores transaction and idempotency record on first call")
        void firstCallStoresTransactionAndRecord() {
            when(idempotencyRecordRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(idempotencyRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean[] replayed = {false};
            TransactionResponse response = transactionService.storeTransaction(
                UUID.randomUUID().toString(), validRequest, replayed);

            assertThat(replayed[0]).isFalse();
            assertThat(response.id()).isNotNull();
            assertThat(response.description()).isEqualTo("Test purchase");

            verify(transactionRepository).save(any(PurchaseTransaction.class));
            verify(idempotencyRecordRepository).save(any(IdempotencyRecord.class));
        }

        @Test
        @DisplayName("idempotency record is saved with correct key")
        void idempotencyRecordHasCorrectKey() {
            String key = UUID.randomUUID().toString();
            when(idempotencyRecordRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(idempotencyRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean[] replayed = {false};
            transactionService.storeTransaction(key, validRequest, replayed);

            ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
            verify(idempotencyRecordRepository).save(captor.capture());
            assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(key);
        }
    }

    // ── Duplicate request (same key, same payload) ────────────────────────────

    @Nested
    @DisplayName("Duplicate request - same key, same payload")
    class DuplicateRequest {

        @Test
        @DisplayName("returns original transaction without a second DB insert")
        void duplicateReturnsOriginalWithoutInsert() {
            String key = UUID.randomUUID().toString();
            UUID originalId = UUID.randomUUID();

            PurchaseTransaction original =
                new PurchaseTransaction("Test purchase", LocalDate.of(2026, 3, 15), new BigDecimal("50.00"));

            // Build the hash the same way TransactionService does
            // We need the existing record to have the correct hash
            // Use the same request to generate a matching record
            when(idempotencyRecordRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.empty())  // first call - new
                .thenReturn(Optional.of(buildMatchingRecord(key, validRequest, originalId)));  // second call - replay
            when(transactionRepository.save(any())).thenReturn(original);
            when(idempotencyRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.findById(any())).thenReturn(Optional.of(original));

            boolean[] replayed1 = {false};
            transactionService.storeTransaction(key, validRequest, replayed1);

            boolean[] replayed2 = {false};
            TransactionResponse response2 = transactionService.storeTransaction(key, validRequest, replayed2);

            assertThat(replayed2[0]).isTrue();
            // Only one DB insert
            verify(transactionRepository, times(1)).save(any());
        }

        private IdempotencyRecord buildMatchingRecord(String key, TransactionRequest req, UUID txId) {
            // Mirror the hash logic from TransactionService
            String payload = req.description() + "|" + req.transactionDate() + "|" + req.amountUsd().toPlainString();
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String hashHex = java.util.HexFormat.of().formatHex(hash);
                return new IdempotencyRecord(key, txId, hashHex);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ── Conflict (same key, different payload) ────────────────────────────────

    @Nested
    @DisplayName("Conflict - same key, different payload")
    class ConflictRequest {

        @Test
        @DisplayName("throws ConflictException when same key used with different payload")
        void differentPayloadThrowsConflict() {
            String key = UUID.randomUUID().toString();

            // Return a record with a hash that WON'T match our new request
            IdempotencyRecord existing = new IdempotencyRecord(key, UUID.randomUUID(), "differenthash");
            when(idempotencyRecordRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(existing));

            boolean[] replayed = {false};
            assertThatThrownBy(() ->
                transactionService.storeTransaction(key, validRequest, replayed))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(key);
        }
    }
}
