package com.wex.currencytranswexlator.exchange.provider;

import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Extension point for currency exchange rate sources.
 *
 * The Treasury API implementation is fully built. Three additional provider
 * stubs (Crypto, Loyalty, Custom) are wired in the registry to demonstrate
 * the plugin architecture. See ProviderRegistry for routing logic.
 *
 * Currency code conventions by provider:
 *   - ISO 4217 fiat (CAD, EUR, GBP...) → TreasuryExchangeRateProvider
 *     NOTE: ISO 4217 -> Treasury name mapping is deferred. See CurrencyCodeMapper.
 *     This POC accepts Treasury native strings directly (e.g. "Canada-Dollar").
 *   - X-prefix (X-BTC, X-ETH)           → CryptoExchangeRateProvider (stub)
 *   - L-prefix (L-MILES, L-POINTS)       → LoyaltyPointsProvider (stub)
 *   - CUSTOM-prefix                       → CustomExchangeProvider (stub)
 */
public interface ExchangeRateProvider {

    /**
     * Returns the most appropriate rate for the given currency on or before
     * the purchaseDate, within a provider-defined lookback window.
     */
    Optional<ExchangeRate> getRate(String currencyCode, LocalDate purchaseDate);

    /**
     * Returns rates for bulk pre-load operations (startup and scheduled refresh).
     * Providers that don't support bulk fetch may return an empty list.
     */
    List<ExchangeRate> getRatesForBulkLoad(String currencyCode, LocalDate from, LocalDate to);

    /** Currency codes (or prefixes) this provider handles. */
    Set<String> getSupportedCurrencies();

    /** Human-readable provider name for logging and health endpoints. */
    String getProviderName();
}
