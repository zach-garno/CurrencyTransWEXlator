package com.wex.currencytranswexlator.exchange.service;

import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import com.wex.currencytranswexlator.exchange.provider.TreasuryApiClient;
import com.wex.currencytranswexlator.exchange.repository.ExchangeRateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages exchange rate pre-loading and scheduled refresh.
 *
 * Strategy (per ADR-03):
 * 1. On application startup: pull rates for the past 365 days (delta from DB max recordDate)
 * 2. Daily scheduled refresh: delta only - records newer than MAX(recordDate) in DB
 * 3. Cache miss in ConversionService triggers on-demand 6-month block fetch
 * 4. Admin endpoint triggers this delta logic immediately on demand
 *
 * Staleness disclosure: ratesAsOf timestamp is exposed on all ConversionResponse
 * objects so the UI can inform users when rates were last refreshed.
 */
@Service
@Slf4j
public class ExchangeRateRefreshService {

    private final TreasuryApiClient treasuryApiClient;
    private final ExchangeRateRepository exchangeRateRepository;
    private final Executor taskExecutor;

    @Value("${exchange-rate.refresh.preload-days:365}")
    private int preloadDays;

    /** Tracks when rates were last successfully refreshed. Exposed via ConversionResponse. */
    private final AtomicReference<Instant> lastRefreshTime = new AtomicReference<>(null);

    public ExchangeRateRefreshService(TreasuryApiClient treasuryApiClient,
                                      ExchangeRateRepository exchangeRateRepository,
                                      @Qualifier("wexTaskExecutor") Executor taskExecutor) {
        this.treasuryApiClient = treasuryApiClient;
        this.exchangeRateRepository = exchangeRateRepository;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Runs once after application context is fully started.
     *
     * Uses direct executor.submit() rather than @Async + @EventListener to avoid
     * Spring AOP proxy initialization ordering issues. @Async on @EventListener
     * methods requires the proxy to be fully created before ApplicationReadyEvent
     * fires - this ordering is not guaranteed and causes context load failures in
     * test environments.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application ready - initiating exchange rate pre-load asynchronously");
        taskExecutor.execute(() -> {
            try {
                runDeltaRefresh();
            } catch (Exception e) {
                log.error("Startup rate pre-load failed", e);
            }
        });
    }

    /**
     * Scheduled delta refresh. Cron configurable via RATE_REFRESH_CRON env var.
     * Default: 02:00 UTC daily. Set to "-" to disable (used in tests).
     */
    @Scheduled(cron = "${exchange-rate.refresh.cron:0 0 2 * * *}")
    public void scheduledRefresh() {
        log.info("Scheduled exchange rate refresh triggered");
        runDeltaRefresh();
    }

    /**
     * Triggered by POST /api/v1/admin/rates/refresh.
     * @Async here is safe: called from a controller, not from an EventListener,
     * so proxy ordering is not a concern.
     */
    @Async
    public void triggerManualRefresh() {
        log.info("Manual rate refresh triggered by admin");
        runDeltaRefresh();
    }

    public Instant getLastRefreshTime() {
        return lastRefreshTime.get();
    }

    // ── Core refresh logic ────────────────────────────────────────────────────

    private void runDeltaRefresh() {
        try {
            LocalDate globalMaxRecordDate = exchangeRateRepository
                .findGlobalMaxRecordDate()
                .orElse(LocalDate.now().minusDays(preloadDays));

            log.info("Delta refresh: fetching records since recordDate={}", globalMaxRecordDate);

            List<TreasuryApiClient.TreasuryRateRecord> records =
                treasuryApiClient.fetchAllRatesSince(globalMaxRecordDate);

            if (records.isEmpty()) {
                log.info("Delta refresh: no new records found since {}", globalMaxRecordDate);
            } else {
                log.info("Delta refresh: persisting {} new records", records.size());
                for (TreasuryApiClient.TreasuryRateRecord record : records) {
                    try {
                        persistIfAbsent(record);
                    } catch (Exception e) {
                        log.warn("Failed to persist rate record {}: {}", record, e.getMessage());
                    }
                }
            }

            lastRefreshTime.set(Instant.now());
            log.info("Exchange rate refresh complete. ratesAsOf={}", lastRefreshTime.get());

        } catch (TreasuryApiClient.TreasuryApiUnavailableException e) {
            log.error("Exchange rate refresh failed - Treasury API unavailable: {}", e.getMessage());
            // Non-fatal: serve existing persisted rates if available.
        } catch (Exception e) {
            log.error("Exchange rate refresh failed with unexpected error", e);
        }
    }

    private void persistIfAbsent(TreasuryApiClient.TreasuryRateRecord record) {
        String currencyCode = record.countryCurrencyDesc();
        LocalDate recordDate = LocalDate.parse(record.recordDate());
        LocalDate effectiveDate = LocalDate.parse(record.effectiveDate());
        BigDecimal rate = new BigDecimal(record.exchangeRate());

        exchangeRateRepository
            .findByCurrencyCodeAndEffectiveDate(currencyCode, effectiveDate)
            .orElseGet(() -> exchangeRateRepository.save(
                new ExchangeRate(currencyCode, effectiveDate, recordDate, rate)
            ));
    }
}
