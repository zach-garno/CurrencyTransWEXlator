package com.wex.currencytranswexlator.exchange.repository;

import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    /**
     * Core temporal lookup: finds the most recent rate for a currency that was
     * effective on or before the purchase date, within the last 6 months.
     *
     * The 6-month window exists because the Treasury dataset records rates
     * periodically (quarterly), not daily. A purchase date of today may only
     * have a matching rate from 3 months ago.
     *
     * If no rate exists within this window, the service returns a 422 -
     * "Purchase cannot be converted to the target currency."
     */
    @Query("""
        SELECT er FROM ExchangeRate er
        WHERE er.currencyCode = :currencyCode
          AND er.effectiveDate <= :purchaseDate
          AND er.effectiveDate >= :sixMonthsEarlier
        ORDER BY er.effectiveDate DESC
        LIMIT 1
        """)
    Optional<ExchangeRate> findMostRecentRateWithinSixMonths(
        @Param("currencyCode") String currencyCode,
        @Param("purchaseDate") LocalDate purchaseDate,
        @Param("sixMonthsEarlier") LocalDate sixMonthsEarlier
    );

    /**
     * Returns the newest recordDate we have for a given currency.
     * Used by the delta refresh job to avoid re-fetching already-persisted data.
     */
    @Query("SELECT MAX(er.recordDate) FROM ExchangeRate er WHERE er.currencyCode = :currencyCode")
    Optional<LocalDate> findMaxRecordDateByCurrencyCode(@Param("currencyCode") String currencyCode);

    /**
     * Returns the newest recordDate across all currencies.
     * Used to determine global delta refresh starting point.
     */
    @Query("SELECT MAX(er.recordDate) FROM ExchangeRate er")
    Optional<LocalDate> findGlobalMaxRecordDate();

    /** Returns all distinct currency codes currently in the local DB. */
    @Query("SELECT DISTINCT er.currencyCode FROM ExchangeRate er ORDER BY er.currencyCode")
    List<String> findAllDistinctCurrencyCodes();

    /** Used for upsert-style behavior during refresh: find existing by unique constraint. */
    Optional<ExchangeRate> findByCurrencyCodeAndEffectiveDate(String currencyCode, LocalDate effectiveDate);
}
