package com.wex.currencytranswexlator.exchange.provider;

import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * STUB: Custom/configurable static rate provider.
 *
 * Supports CUSTOM-prefix codes with rates defined in application YAML.
 * Use cases: private exchange networks, barter systems, internal transfer pricing.
 *
 * Production implementation would read from:
 *   exchange-rate.custom-rates:
 *     CUSTOM-BARTER: 0.85
 *     CUSTOM-INTERNAL: 1.05
 *
 * Not implemented in this POC. Present to demonstrate the plugin architecture.
 */
@Component
@Slf4j
public class CustomExchangeProvider implements ExchangeRateProvider {

    @Override
    public Optional<ExchangeRate> getRate(String currencyCode, LocalDate purchaseDate) {
        log.warn("CustomExchangeProvider is a stub - not implemented");
        return Optional.empty();
    }

    @Override
    public List<ExchangeRate> getRatesForBulkLoad(String currencyCode, LocalDate from, LocalDate to) {
        return Collections.emptyList();
    }

    @Override
    public Set<String> getSupportedCurrencies() {
        return Set.of("CUSTOM-BARTER", "CUSTOM-INTERNAL");
    }

    @Override
    public String getProviderName() {
        return "Custom Exchange Provider (stub)";
    }
}
