package com.wex.currencytranswexlator.exchange.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Routes currency code requests to the appropriate ExchangeRateProvider.
 *
 * Routing convention:
 *   - "X-" prefix → CryptoExchangeRateProvider
 *   - "L-" prefix → LoyaltyPointsProvider
 *   - "CUSTOM-" prefix → CustomExchangeProvider
 *   - All others → TreasuryExchangeRateProvider (Treasury native strings)
 *
 * All providers are Spring-managed beans injected by the List<ExchangeRateProvider>
 * constructor. Adding a new provider requires only implementing the interface
 * and annotating with @Component - no registry changes needed.
 */
@Component
@Slf4j
public class ProviderRegistry {

    private final TreasuryExchangeRateProvider treasuryProvider;
    private final List<ExchangeRateProvider> allProviders;

    public ProviderRegistry(TreasuryExchangeRateProvider treasuryProvider,
                            List<ExchangeRateProvider> allProviders) {
        this.treasuryProvider = treasuryProvider;
        this.allProviders = allProviders;
        log.info("ProviderRegistry initialized with {} providers: {}",
            allProviders.size(),
            allProviders.stream().map(ExchangeRateProvider::getProviderName).toList());
    }

    public ExchangeRateProvider resolve(String currencyCode) {
        if (currencyCode == null) {
            return treasuryProvider;
        }
        if (currencyCode.startsWith("X-")) {
            return findProvider(CryptoExchangeRateProvider.class);
        }
        if (currencyCode.startsWith("L-")) {
            return findProvider(LoyaltyPointsProvider.class);
        }
        if (currencyCode.startsWith("CUSTOM-")) {
            return findProvider(CustomExchangeProvider.class);
        }
        return treasuryProvider;
    }

    private ExchangeRateProvider findProvider(Class<? extends ExchangeRateProvider> type) {
        return allProviders.stream()
            .filter(type::isInstance)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Provider not found: " + type.getSimpleName()));
    }
}
