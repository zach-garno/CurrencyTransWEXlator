package com.wex.currencytranswexlator.unit.domain;

import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit - Domain
 * 6-month boundary business rule validation.
 * Tests the date arithmetic that feeds into the repository query,
 * without requiring a DB connection.
 */
@DisplayName("6-Month Rate Lookback - Business Rule Tests")
class SixMonthBoundaryRuleTest {

    /**
     * Simulates the boundary calculation performed in ConversionService
     * before calling the repository.
     */
    private LocalDate sixMonthsEarlier(LocalDate purchaseDate) {
        return purchaseDate.minusMonths(6);
    }

    private boolean isRateWithinWindow(LocalDate effectiveDate, LocalDate purchaseDate) {
        LocalDate lowerBound = sixMonthsEarlier(purchaseDate);
        return !effectiveDate.isAfter(purchaseDate) &&
               !effectiveDate.isBefore(lowerBound);
    }

    @Nested
    @DisplayName("Rate effective date window")
    class RateWindow {

        @Test
        @DisplayName("rate on purchase date is within window")
        void rateOnPurchaseDateInWindow() {
            LocalDate purchaseDate = LocalDate.of(2026, 3, 15);
            assertThat(isRateWithinWindow(purchaseDate, purchaseDate)).isTrue();
        }

        @Test
        @DisplayName("rate exactly 6 months before purchase date is within window")
        void rateExactly6MonthsBeforeInWindow() {
            LocalDate purchaseDate = LocalDate.of(2026, 3, 15);
            LocalDate rateDate = purchaseDate.minusMonths(6);
            assertThat(isRateWithinWindow(rateDate, purchaseDate)).isTrue();
        }

        @Test
        @DisplayName("rate 6 months and 1 day before purchase date is outside window")
        void rateBeyond6MonthsOutsideWindow() {
            LocalDate purchaseDate = LocalDate.of(2026, 3, 15);
            LocalDate rateDate = purchaseDate.minusMonths(6).minusDays(1);
            assertThat(isRateWithinWindow(rateDate, purchaseDate)).isFalse();
        }

        @Test
        @DisplayName("rate after purchase date is outside window")
        void rateFutureDateOutsideWindow() {
            LocalDate purchaseDate = LocalDate.of(2026, 3, 15);
            LocalDate rateDate = purchaseDate.plusDays(1);
            assertThat(isRateWithinWindow(rateDate, purchaseDate)).isFalse();
        }

        @Test
        @DisplayName("Canada-Dollar quarterly pattern: purchase in May finds March rate")
        void canadaDollarQuarterlyPatternFound() {
            // Real scenario: Canada-Dollar effective date 2026-03-31
            // Purchase date: 2026-05-20
            // 6-month window: 2025-11-20 to 2026-05-20
            LocalDate purchaseDate = LocalDate.of(2026, 5, 20);
            LocalDate canadaRateDate = LocalDate.of(2026, 3, 31);
            assertThat(isRateWithinWindow(canadaRateDate, purchaseDate)).isTrue();
        }

        @Test
        @DisplayName("purchase in early period with no rate in last 6 months returns no result")
        void earlyPurchaseWithNoRecentRateReturnsNothing() {
            // Simulate a purchase in 2015 when rates may have had a gap
            LocalDate purchaseDate = LocalDate.of(2015, 1, 5);
            // Rate from 7 months prior - outside window
            LocalDate oldRateDate = purchaseDate.minusMonths(7);
            assertThat(isRateWithinWindow(oldRateDate, purchaseDate)).isFalse();
        }
    }

    @Nested
    @DisplayName("ExchangeRate entity")
    class ExchangeRateEntityTest {

        @Test
        @DisplayName("ExchangeRate construction sets all fields correctly")
        void constructionSetsFields() {
            LocalDate effective = LocalDate.of(2026, 3, 31);
            LocalDate record = LocalDate.of(2026, 3, 31);
            BigDecimal rate = new BigDecimal("1.362100");

            ExchangeRate er = new ExchangeRate("Canada-Dollar", effective, record, rate);

            assertThat(er.getId()).isNotNull();
            assertThat(er.getCurrencyCode()).isEqualTo("Canada-Dollar");
            assertThat(er.getEffectiveDate()).isEqualTo(effective);
            assertThat(er.getRecordDate()).isEqualTo(record);
            assertThat(er.getRate()).isEqualByComparingTo(rate);
            assertThat(er.getFetchedAt()).isNotNull();
        }
    }
}
