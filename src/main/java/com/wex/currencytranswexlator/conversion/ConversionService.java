package com.wex.currencytranswexlator.conversion;

import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import com.wex.currencytranswexlator.exchange.provider.ProviderRegistry;
import com.wex.currencytranswexlator.exchange.service.ExchangeRateRefreshService;
import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;
import com.wex.currencytranswexlator.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversionService {

    private static final String USD = "USD";

    private final TransactionService transactionService;
    private final ProviderRegistry providerRegistry;
    private final ExchangeRateRefreshService refreshService;

    /**
     * Retrieves a transaction and converts it to the requested currency.
     *
     * USD pass-through: if currency is null or "USD", returns the original amount
     * with rate=1.0 and no Treasury API call. This is also the default behavior.
     *
     * Conversion math: the Treasury API rate is applied as:
     *   convertedAmount = amountUsd * exchangeRate
     *
     * Rounding: HALF_UP to 2 decimal places at the display layer only.
     * No financial math is performed beyond this single multiplication and round.
     * amountUsd is stored and retrieved as-is (DECIMAL precision, no rounding on storage).
     *
     * 6-month rule: if no rate exists within 6 months on or before the purchase date,
     * a RateNotFoundException is thrown → 422 "Purchase cannot be converted to the target currency."
     */
    public ConversionResponse convert(UUID transactionId, String currencyCode) {
        PurchaseTransaction transaction = transactionService.getTransactionOrThrow(transactionId);

        // USD pass-through (default behavior, no API call needed)
        if (currencyCode == null || currencyCode.isBlank() || USD.equalsIgnoreCase(currencyCode)) {
            log.debug("USD pass-through for transaction={}", transactionId);
            return ConversionResponse.usdPassThrough(transaction, Instant.now());
        }

        Optional<ExchangeRate> rateOpt = providerRegistry
            .resolve(currencyCode)
            .getRate(currencyCode, transaction.getTransactionDate());

        if (rateOpt.isEmpty()) {
            log.warn("No exchange rate found for currency={} transactionDate={}",
                currencyCode, transaction.getTransactionDate());
            throw new RateNotFoundException(
                "Purchase cannot be converted to the target currency.");
        }

        ExchangeRate rate = rateOpt.get();

        /*
         * Display-layer rounding only.
         * The Treasury API exchange rate is applied here. amountUsd is stored
         * as-is; no rounding occurred on storage. The result is rounded to 2dp
         * HALF_UP at this boundary - the last step before response serialization.
         *
         * NOTE: In a higher-stakes scenario this would use BigDecimal arithmetic
         * throughout. For this exercise scope the Treasury API defines the rate
         * precision and the requirement specifies 2dp output rounding.
         */
        BigDecimal convertedAmount = transaction.getAmountUsd()
            .multiply(rate.getRate())
            .setScale(2, RoundingMode.HALF_UP);

        Instant ratesAsOf = refreshService.getLastRefreshTime() != null
            ? refreshService.getLastRefreshTime()
            : Instant.now();

        log.debug("Conversion: transactionId={} currency={} rate={} convertedAmount={}",
            transactionId, currencyCode, rate.getRate(), convertedAmount);

        return ConversionResponse.builder()
            .id(transaction.getId())
            .description(transaction.getDescription())
            .transactionDate(transaction.getTransactionDate())
            .amountUsd(transaction.getAmountUsd())
            .exchangeRate(rate.getRate())
            .convertedAmount(convertedAmount)
            .currencyCode(currencyCode)
            .ratesAsOf(ratesAsOf)
            .build();
    }

    public static class RateNotFoundException extends RuntimeException {
        public RateNotFoundException(String message) {
            super(message);
        }
    }
}
