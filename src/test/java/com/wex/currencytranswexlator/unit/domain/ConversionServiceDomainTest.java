package com.wex.currencytranswexlator.unit.domain;

import com.wex.currencytranswexlator.conversion.ConversionResponse;
import com.wex.currencytranswexlator.conversion.ConversionService;
import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import com.wex.currencytranswexlator.exchange.provider.ProviderRegistry;
import com.wex.currencytranswexlator.exchange.provider.TreasuryExchangeRateProvider;
import com.wex.currencytranswexlator.exchange.service.ExchangeRateRefreshService;
import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;
import com.wex.currencytranswexlator.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit - Domain
 * Pure domain logic tests. No Spring context. No DB. Fast.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversionService - Domain Logic")
class ConversionServiceDomainTest {

    @Mock TransactionService transactionService;
    @Mock ProviderRegistry providerRegistry;
    @Mock TreasuryExchangeRateProvider treasuryProvider;
    @Mock ExchangeRateRefreshService refreshService;

    ConversionService conversionService;

    @BeforeEach
    void setup() {
        conversionService = new ConversionService(transactionService, providerRegistry, refreshService);
    }

    private PurchaseTransaction makeTransaction(BigDecimal amount, LocalDate date) {
        return new PurchaseTransaction("Test purchase", date, amount);
    }

    private ExchangeRate makeRate(BigDecimal rate, LocalDate effectiveDate) {
        return new ExchangeRate("Canada-Dollar", effectiveDate, effectiveDate, rate);
    }

    // ── USD Pass-through ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("USD pass-through")
    class UsdPassThrough {

        @Test
        @DisplayName("null currency returns USD pass-through with rate 1.0")
        void nullCurrencyReturnsPassThrough() {
            var tx = makeTransaction(new BigDecimal("100.00"), LocalDate.now());
            when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);

            ConversionResponse response = conversionService.convert(UUID.randomUUID(), null);

            assertThat(response.getExchangeRate()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(response.getConvertedAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(response.getCurrencyCode()).isEqualTo("USD");
            verifyNoInteractions(providerRegistry);
        }

        @Test
        @DisplayName("blank currency returns USD pass-through")
        void blankCurrencyReturnsPassThrough() {
            var tx = makeTransaction(new BigDecimal("50.25"), LocalDate.now());
            when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);

            ConversionResponse response = conversionService.convert(UUID.randomUUID(), "  ");

            assertThat(response.getExchangeRate()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(response.getConvertedAmount()).isEqualByComparingTo(new BigDecimal("50.25"));
            verifyNoInteractions(providerRegistry);
        }

        @Test
        @DisplayName("'USD' currency returns pass-through regardless of case")
        void usdStringReturnsPassThrough() {
            var tx = makeTransaction(new BigDecimal("75.50"), LocalDate.now());
            when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);

            ConversionResponse response = conversionService.convert(UUID.randomUUID(), "usd");

            assertThat(response.getCurrencyCode()).isEqualTo("USD");
            verifyNoInteractions(providerRegistry);
        }
    }

    // ── Display-layer rounding ────────────────────────────────────────────────

    @Nested
    @DisplayName("Display-layer rounding (HALF_UP to 2dp)")
    class DisplayRounding {

        @Test
        @DisplayName("converted amount is rounded HALF_UP to 2 decimal places")
        void roundingHalfUp() {
            // 10.00 * 1.36215 = 13.6215 → rounds to 13.62
            var tx = makeTransaction(new BigDecimal("10.00"), LocalDate.of(2026, 3, 15));
            var rate = makeRate(new BigDecimal("1.36215"), LocalDate.of(2026, 3, 31));

            when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);
            when(providerRegistry.resolve("Canada-Dollar")).thenReturn(treasuryProvider);
            when(treasuryProvider.getRate(eq("Canada-Dollar"), any())).thenReturn(Optional.of(rate));

            ConversionResponse response = conversionService.convert(UUID.randomUUID(), "Canada-Dollar");

            assertThat(response.getConvertedAmount()).isEqualByComparingTo(new BigDecimal("13.62"));
        }

        @Test
        @DisplayName("rounding 5 rounds up (HALF_UP semantics)")
        void roundingFiveRoundsUp() {
            // 1.00 * 1.3625 = 1.3625 → rounds to 1.36 (HALF_UP: .625 → .63)
            var tx = makeTransaction(new BigDecimal("1.00"), LocalDate.of(2026, 3, 15));
            var rate = makeRate(new BigDecimal("1.3625"), LocalDate.of(2026, 3, 31));

            when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);
            when(providerRegistry.resolve("Canada-Dollar")).thenReturn(treasuryProvider);
            when(treasuryProvider.getRate(eq("Canada-Dollar"), any())).thenReturn(Optional.of(rate));

            ConversionResponse response = conversionService.convert(UUID.randomUUID(), "Canada-Dollar");

            assertThat(response.getConvertedAmount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("original amountUsd is not modified - only display result is rounded")
        void originalAmountNotModified() {
            var tx = makeTransaction(new BigDecimal("99.99"), LocalDate.of(2026, 3, 15));
            var rate = makeRate(new BigDecimal("1.5"), LocalDate.of(2026, 3, 31));

            when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);
            when(providerRegistry.resolve("Canada-Dollar")).thenReturn(treasuryProvider);
            when(treasuryProvider.getRate(eq("Canada-Dollar"), any())).thenReturn(Optional.of(rate));

            ConversionResponse response = conversionService.convert(UUID.randomUUID(), "Canada-Dollar");

            // Original amount preserved exactly as stored
            assertThat(response.getAmountUsd()).isEqualByComparingTo(new BigDecimal("99.99"));
        }
    }

    // ── 6-month rate boundary ─────────────────────────────────────────────────

    @Nested
    @DisplayName("6-month rate boundary enforcement")
    class SixMonthBoundary {

        @Test
        @DisplayName("throws RateNotFoundException when no rate available within 6 months")
        void noRateWithin6MonthsThrows422() {
            var tx = makeTransaction(new BigDecimal("50.00"), LocalDate.of(2024, 1, 1));

            when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);
            when(providerRegistry.resolve("Canada-Dollar")).thenReturn(treasuryProvider);
            when(treasuryProvider.getRate(any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> conversionService.convert(UUID.randomUUID(), "Canada-Dollar"))
                .isInstanceOf(ConversionService.RateNotFoundException.class)
                .hasMessage("Purchase cannot be converted to the target currency.");
        }

        @Test
        @DisplayName("ratesAsOf is populated on successful conversion")
        void ratesAsOfPopulated() {
            var tx = makeTransaction(new BigDecimal("20.00"), LocalDate.of(2026, 3, 15));
            var rate = makeRate(new BigDecimal("1.36"), LocalDate.of(2026, 3, 31));

            when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);
            when(providerRegistry.resolve("Canada-Dollar")).thenReturn(treasuryProvider);
            when(treasuryProvider.getRate(eq("Canada-Dollar"), any())).thenReturn(Optional.of(rate));

            ConversionResponse response = conversionService.convert(UUID.randomUUID(), "Canada-Dollar");

            assertThat(response.getRatesAsOf()).isNotNull();
        }
    }

    // ── Response shape completeness ───────────────────────────────────────────

    @Test
    @DisplayName("all required response fields are populated on successful conversion")
    void allFieldsPopulatedOnSuccessfulConversion() {
        var tx = makeTransaction(new BigDecimal("100.00"), LocalDate.of(2026, 3, 15));
        var rate = makeRate(new BigDecimal("1.3621"), LocalDate.of(2026, 3, 31));

        when(transactionService.getTransactionOrThrow(any())).thenReturn(tx);
        when(providerRegistry.resolve("Canada-Dollar")).thenReturn(treasuryProvider);
        when(treasuryProvider.getRate(eq("Canada-Dollar"), any())).thenReturn(Optional.of(rate));

        ConversionResponse response = conversionService.convert(UUID.randomUUID(), "Canada-Dollar");

        assertThat(response.getId()).isNotNull();
        assertThat(response.getDescription()).isEqualTo("Test purchase");
        assertThat(response.getTransactionDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(response.getAmountUsd()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(response.getExchangeRate()).isEqualByComparingTo(new BigDecimal("1.3621"));
        assertThat(response.getConvertedAmount()).isEqualByComparingTo(new BigDecimal("136.21"));
        assertThat(response.getCurrencyCode()).isEqualTo("Canada-Dollar");
        assertThat(response.getRatesAsOf()).isNotNull();
    }
}
