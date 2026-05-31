package com.wex.currencytranswexlator.conversion;

import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model returned by GET /api/v1/transactions/{id}/convert.
 * Not persisted - assembled on retrieval.
 *
 * ratesAsOf: reflects the last successful exchange rate refresh time.
 * Exposed so UIs can inform users of potential data staleness.
 * See ADR-03: 24-hour refresh window, quarterly Treasury publication cadence.
 */
@Value
@Builder
public class ConversionResponse {

    UUID id;
    String description;
    LocalDate transactionDate;
    BigDecimal amountUsd;
    BigDecimal exchangeRate;
    BigDecimal convertedAmount;
    String currencyCode;

    /**
     * Timestamp of the last successful exchange rate refresh.
     * Displayed to users as "rates current as of [time]".
     * Used to assess potential staleness of conversion data, given Treasury's quarterly publication schedule.
     * Not the transaction date - reflects when the exchange rates were last updated, not when the transaction occurred.
     */
    Instant ratesAsOf;

    /** USD pass-through factory method - no conversion needed. */
    public static ConversionResponse usdPassThrough(PurchaseTransaction tx, Instant ratesAsOf) {
        return ConversionResponse.builder()
            .id(tx.getId())
            .description(tx.getDescription())
            .transactionDate(tx.getTransactionDate())
            .amountUsd(tx.getAmountUsd())
            .exchangeRate(BigDecimal.ONE)
            .convertedAmount(tx.getAmountUsd())
            .currencyCode("USD")
            .ratesAsOf(ratesAsOf)
            .build();
    }
}
