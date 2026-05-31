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
 * STUB: Loyalty points exchange rate provider.
 *
 * Handles L-prefix codes (L-MILES, L-POINTS, L-REWARDS).
 * Models points-to-currency equivalence for loyalty program integrations.
 *
 * Not implemented in this POC. Present to demonstrate the plugin architecture.
 */
@Component
@Slf4j
public class LoyaltyPointsProvider implements ExchangeRateProvider {

    @Override
    public Optional<ExchangeRate> getRate(String currencyCode, LocalDate purchaseDate) {
        log.warn("LoyaltyPointsProvider is a stub - not implemented");
        return Optional.empty();
    }

    @Override
    public List<ExchangeRate> getRatesForBulkLoad(String currencyCode, LocalDate from, LocalDate to) {
        return Collections.emptyList();
    }

    @Override
    public Set<String> getSupportedCurrencies() {
        return Set.of("L-MILES", "L-POINTS", "L-REWARDS");
    }

    @Override
    public String getProviderName() {
        return "Loyalty Points Provider (stub)";
    }
}
