package com.wex.currencytranswexlator.common.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Scheduled cleanup of expired idempotency records.
 *
 * Idempotency records have a 24-hour TTL. This job removes them after
 * expiry to prevent unbounded table growth. Runs daily at 03:00 UTC -
 * offset from the rate refresh job (02:00 UTC) to avoid DB contention.
 *
 * In production at scale, this could be replaced with a PostgreSQL
 * partition strategy or pg_cron if the idempotency table grows large.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyCleanupJob {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredRecords() {
        int deleted = idempotencyRecordRepository.deleteExpiredRecords(Instant.now());
        log.info("Idempotency cleanup: removed {} expired records", deleted);
    }
}
