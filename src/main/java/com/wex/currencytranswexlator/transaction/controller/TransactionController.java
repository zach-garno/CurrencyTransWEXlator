package com.wex.currencytranswexlator.transaction.controller;

import com.wex.currencytranswexlator.conversion.ConversionResponse;
import com.wex.currencytranswexlator.conversion.ConversionService;
import com.wex.currencytranswexlator.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transactions", description = "Purchase transaction storage and currency conversion")
public class TransactionController {

    static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
    static final String IDEMPOTENCY_REPLAYED_HEADER = "X-Idempotency-Replayed";

    private final TransactionService transactionService;
    private final ConversionService conversionService;

    @PostMapping
    @Operation(
        summary = "Store a purchase transaction",
        description = "Accepts and persists a purchase transaction in USD. " +
                      "Requires X-Idempotency-Key header to prevent duplicate submissions."
    )
    @ApiResponse(responseCode = "201", description = "Transaction stored successfully")
    @ApiResponse(responseCode = "400", description = "Validation failure - see field errors")
    @ApiResponse(responseCode = "409", description = "Idempotency key reused with different payload")
    public ResponseEntity<TransactionResponse> storeTransaction(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
        @Valid @RequestBody TransactionRequest request
    ) {
        MDC.put("correlationId", idempotencyKey);
        try {
            boolean[] replayed = {false};
            TransactionResponse response = transactionService.storeTransaction(
                idempotencyKey, request, replayed);

            return ResponseEntity
                .status(HttpStatus.CREATED)
                .header(IDEMPOTENCY_REPLAYED_HEADER, String.valueOf(replayed[0]))
                .body(response);
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/{id}/convert")
    @Operation(
        summary = "Retrieve transaction with currency conversion",
        description = "Retrieves a stored transaction and converts the USD amount to the " +
                      "specified currency using the Treasury Reporting Rates of Exchange API. " +
                      "If currency is omitted or 'USD', returns the original amount (pass-through). " +
                      "Uses the most recent rate on or before the transaction date within 6 months."
    )
    @ApiResponse(responseCode = "200", description = "Transaction retrieved and converted")
    @ApiResponse(responseCode = "404", description = "Transaction not found")
    @ApiResponse(responseCode = "422", description = "Purchase cannot be converted to the target currency")
    @ApiResponse(responseCode = "503", description = "Exchange rate service temporarily unavailable")
    public ResponseEntity<ConversionResponse> getTransactionWithConversion(
        @PathVariable UUID id,
        @Parameter(description = "Treasury API currency string (e.g. 'Canada-Dollar'). Defaults to USD.")
        @RequestParam(required = false) String currency
    ) {
        MDC.put("correlationId", id.toString());
        try {
            return ResponseEntity.ok(conversionService.convert(id, currency));
        } finally {
            MDC.clear();
        }
    }
}
