package com.wex.currencytranswexlator.transaction.controller;

import com.wex.currencytranswexlator.exchange.repository.ExchangeRateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/currencies")
@RequiredArgsConstructor
@Tag(name = "Currencies", description = "Supported currency lookup")
public class CurrenciesController {

    private final ExchangeRateRepository exchangeRateRepository;

    @GetMapping
    @Operation(
        summary = "List supported currencies",
        description = "Returns all currency strings currently available in the local exchange rate DB. " +
                      "Reflects the last successful refresh, not a live Treasury API call."
    )
    public ResponseEntity<List<String>> getSupportedCurrencies() {
        return ResponseEntity.ok(exchangeRateRepository.findAllDistinctCurrencyCodes());
    }
}
