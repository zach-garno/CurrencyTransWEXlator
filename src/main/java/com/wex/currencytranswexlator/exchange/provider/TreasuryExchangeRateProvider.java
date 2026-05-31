package com.wex.currencytranswexlator.exchange.provider;

import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import com.wex.currencytranswexlator.exchange.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class TreasuryExchangeRateProvider implements ExchangeRateProvider {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TreasuryApiClient treasuryApiClient;
    private final ExchangeRateRepository exchangeRateRepository;

    @Override
    public Optional<ExchangeRate> getRate(String currencyCode, LocalDate purchaseDate) {
        LocalDate sixMonthsEarlier = purchaseDate.minusMonths(6);

        // Check local DB first (startup pre-load covers most cases)
        Optional<ExchangeRate> cached = exchangeRateRepository
            .findMostRecentRateWithinSixMonths(currencyCode, purchaseDate, sixMonthsEarlier);

        if (cached.isPresent()) {
            log.debug("Rate found in local DB: currency={} effectiveDate={}",
                currencyCode, cached.get().getEffectiveDate());
            return cached;
        }

        // Cache miss - pull 6-month block for this currency from Treasury API
        log.info("Cache miss for currency={} purchaseDate={}. Fetching from Treasury API.", currencyCode, purchaseDate);
        List<TreasuryApiClient.TreasuryRateRecord> records =
            treasuryApiClient.fetchRates(currencyCode, sixMonthsEarlier);

        persistRecords(records);

        // Re-query after persist
        return exchangeRateRepository
            .findMostRecentRateWithinSixMonths(currencyCode, purchaseDate, sixMonthsEarlier);
    }

    @Override
    public List<ExchangeRate> getRatesForBulkLoad(String currencyCode, LocalDate from, LocalDate to) {
        List<TreasuryApiClient.TreasuryRateRecord> records =
            treasuryApiClient.fetchRates(currencyCode, from);
        return persistRecords(records);
    }

    @Override
    public Set<String> getSupportedCurrencies() {
        return Set.copyOf(exchangeRateRepository.findAllDistinctCurrencyCodes());
    }

    @Override
    public String getProviderName() {
        return "U.S. Treasury Reporting Rates of Exchange";
    }

    private List<ExchangeRate> persistRecords(List<TreasuryApiClient.TreasuryRateRecord> records) {
        return records.stream()
            .map(this::toEntity)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(this::upsert)
            .collect(Collectors.toList());
    }

    private Optional<ExchangeRate> toEntity(TreasuryApiClient.TreasuryRateRecord record) {
        try {
            // Treasury API response has "country_currency_desc" (e.g. "Canada-Dollar")
            // We parse it into separate country and currency codes.
            String currencyCode = record.countryCurrencyDesc();
            LocalDate recordDate = LocalDate.parse(record.recordDate(), DATE_FMT);
            LocalDate effectiveDate = LocalDate.parse(record.effectiveDate(), DATE_FMT);
            BigDecimal rate = new BigDecimal(record.exchangeRate());

            return Optional.of(new ExchangeRate(
                currencyCode, effectiveDate, recordDate, rate));
        } catch (Exception e) {
            log.warn("Skipping malformed Treasury API record: {} - {}", record, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Insert-or-ignore on the unique constraint (currencyCode, effectiveDate).
     * If the record already exists, return the existing one without an update.
     * Exchange rates are immutable facts once published.
     */
    private ExchangeRate upsert(ExchangeRate candidate) {
        return exchangeRateRepository
            .findByCurrencyCodeAndEffectiveDate(candidate.getCurrencyCode(), candidate.getEffectiveDate())
            .orElseGet(() -> exchangeRateRepository.save(candidate));
    }
}
