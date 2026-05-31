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
 * STUB: Crypto exchange rate provider.
 *
 * Handles X-prefix currency codes (X-BTC, X-ETH, X-SOL).
 * Production target: CoinGecko API for real-time crypto-to-USD rates.
 *
 * Not implemented in this POC. Present to demonstrate the plugin architecture
 * defined by ExchangeRateProvider and ProviderRegistry.
 */
@Component
@Slf4j
public class CryptoExchangeRateProvider implements ExchangeRateProvider {

    @Override
    public Optional<ExchangeRate> getRate(String currencyCode, LocalDate purchaseDate) {
        // STUB: would call CoinGecko /coins/{id}/history?date={date}
        log.warn("CryptoExchangeRateProvider is a stub - not implemented");
        return Optional.empty();
    }

    @Override
    public List<ExchangeRate> getRatesForBulkLoad(String currencyCode, LocalDate from, LocalDate to) {
        return Collections.emptyList();
    }

    @Override
    public Set<String> getSupportedCurrencies() {
        // X-prefix convention for crypto assets
        return Set.of("X-BTC", "X-ETH", "X-SOL");
    }

    @Override
    public String getProviderName() {
        return "Crypto Exchange Rate Provider (stub)";
    }
}
