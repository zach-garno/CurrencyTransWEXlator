package com.wex.currencytranswexlator.exchange.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * HTTP client for the U.S. Treasury Reporting Rates of Exchange API.
 *
 * API reference: https://fiscaldata.treasury.gov/datasets/treasury-reporting-rates-exchange/
 *
 * Circuit breaker "treasury-api" configured in application.yml:
 *   - 50% failure rate over 10-call window opens the circuit
 *   - 30-second wait before half-open
 *   - 5-second timeout per call (via Resilience4j TimeLimiter)
 *
 * When the circuit is open, callers receive a ServiceUnavailableException.
 * If a locally persisted rate is available, ConversionService will serve it.
 * If no local rate exists, the endpoint returns 503 with Retry-After.
 */
@Component
@Slf4j
public class TreasuryApiClient {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int PAGE_SIZE = 500;

    private final WebClient webClient;

    public TreasuryApiClient(WebClient.Builder builder,
                             @Value("${treasury.api.base-url}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Fetches all exchange rates for a specific currency published on or after fromDate.
     * Used by delta refresh and cache-miss single-currency lookups.
     */
    @CircuitBreaker(name = "treasury-api", fallbackMethod = "fetchRatesFallback")
    public List<TreasuryRateRecord> fetchRates(String currencyCode, LocalDate fromDate) {
        log.info("Fetching Treasury rates: currency={} fromDate={}", currencyCode, fromDate);

        String filter = String.format(
            "country_currency_desc:eq:%s,record_date:gte:%s",
            currencyCode,
            fromDate.format(DATE_FMT)
        );

        TreasuryApiResponse response = webClient.get()
            // fetchRates — URI builder had an issue with the page size filter '[' and ']'
            .uri(String.format(
                "?fields=country_currency_desc,exchange_rate,effective_date,record_date" +
                "&filter=country_currency_desc:eq:%s,record_date:gte:%s" +
                "&sort=-effective_date&page[size]=%d",
                currencyCode.replace(" ", "%20"),   // "Euro Zone-Euro" has a space
                fromDate.format(DATE_FMT),
                PAGE_SIZE
            ))
            .retrieve()
            .bodyToMono(TreasuryApiResponse.class)
            .block();

        if (response == null || response.data() == null) {
            log.warn("Treasury API returned empty response for currency={}", currencyCode);
            return Collections.emptyList();
        }

        log.info("Treasury API returned {} records for currency={}", response.data().size(), currencyCode);
        return response.data();
    }

    /**
     * Fetches all available rates (all currencies) published on or after fromDate.
     * Used by startup pre-load and full delta refresh.
     */
    @CircuitBreaker(name = "treasury-api", fallbackMethod = "fetchAllRatesFallback")
    public List<TreasuryRateRecord> fetchAllRatesSince(LocalDate fromDate) {
        log.info("Fetching all Treasury rates since {}", fromDate);

        String filter = String.format("record_date:gte:%s", fromDate.format(DATE_FMT));

        TreasuryApiResponse response = webClient.get()
            // fetchAllRatesSince — URI builder had an issue with the page size filter '[' and ']'
            .uri(String.format(
                "?fields=country_currency_desc,exchange_rate,effective_date,record_date" +
                "&filter=record_date:gte:%s" +
                "&sort=-effective_date&page[size]=%d",
                fromDate.format(DATE_FMT),
                PAGE_SIZE
            ))
            .retrieve()
            .bodyToMono(TreasuryApiResponse.class)
            .block();

        if (response == null || response.data() == null) {
            log.warn("Treasury API returned empty response for bulk fetch since {}", fromDate);
            return Collections.emptyList();
        }

        log.info("Treasury API bulk fetch returned {} records", response.data().size());
        return response.data();
    }

    // ── Fallback methods ──────────────────────────────────────────────────────

    public List<TreasuryRateRecord> fetchRatesFallback(String currencyCode, LocalDate fromDate, Throwable t) {
        log.warn("Treasury API circuit open or error for currency={}: {}", currencyCode, t.getMessage());
        throw new TreasuryApiUnavailableException(
            "Treasury API unavailable. Serving locally persisted rates if available.", t);
    }

    public List<TreasuryRateRecord> fetchAllRatesFallback(LocalDate fromDate, Throwable t) {
        log.warn("Treasury API bulk fetch failed: {}", t.getMessage());
        throw new TreasuryApiUnavailableException(
            "Treasury API unavailable during bulk fetch.", t);
    }

    // ── Response DTOs ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TreasuryApiResponse(List<TreasuryRateRecord> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TreasuryRateRecord(
        @JsonProperty("country_currency_desc") String countryCurrencyDesc,
        @JsonProperty("exchange_rate") String exchangeRate,
        @JsonProperty("record_date") String recordDate,
        @JsonProperty("effective_date") String effectiveDate
    ) {}

    public static class TreasuryApiUnavailableException extends RuntimeException {
        public TreasuryApiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
