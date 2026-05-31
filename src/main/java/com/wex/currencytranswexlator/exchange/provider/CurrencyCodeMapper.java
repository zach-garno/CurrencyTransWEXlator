package com.wex.currencytranswexlator.exchange.provider;

/**
 * Interface for mapping between ISO 4217 currency codes and Treasury API naming.
 *
 * The Treasury Reporting Rates of Exchange API uses its own country-currency
 * naming convention (e.g. "Canada-Dollar") rather than ISO 4217 codes (CAD).
 *
 * PRODUCTION REQUIREMENT: A full implementation of this interface would maintain
 * the complete ISO 4217 -> Treasury name mapping, enabling the API to accept
 * standard currency codes as input.
 *
 * POC DECISION: This interface is intentionally not implemented in this submission.
 * The API accepts Treasury native strings directly. The interface stub signals
 * awareness of the gap and provides the extension point for production implementation.
 * Adding the full mapping is domain maintenance work without meaningful value
 * at this exercise scope.
 *
 * See ADR-07 and HR-06 in the DHR for rationale.
 */
public interface CurrencyCodeMapper {

    /**
     * Converts an ISO 4217 code to the Treasury API currency string.
     * Example: "CAD" → "Canada-Dollar"
     */
    String toTreasuryName(String iso4217Code);

    /**
     * Converts a Treasury API currency string to the ISO 4217 code.
     * Example: "Canada-Dollar" → "CAD"
     */
    String fromTreasuryName(String treasuryName);

    /** Returns true if the given code is a recognized ISO 4217 code. */
    boolean isKnownIso4217Code(String code);
}
