package com.wex.currencytranswexlator.transaction.controller;

import com.wex.currencytranswexlator.exchange.service.ExchangeRateRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin", description = "Administrative operations")
public class AdminController {

    private final ExchangeRateRefreshService refreshService;

    @PostMapping("/rates/refresh")
    @Operation(
        summary = "Trigger manual rate refresh",
        description = "Triggers an immediate delta refresh of exchange rates from the Treasury API. " +
                      "Runs asynchronously - does not block the response. " +
                      "Use when a known Treasury rate update has occurred within the 24-hour refresh window. " +
                      "NOTE: This endpoint should be network-restricted (VPC-only) in production. " +
                      "Authentication/authorization is out of scope for this POC."
    )
    public ResponseEntity<Map<String, Object>> triggerRefresh() {
        log.info("Admin manual rate refresh requested");
        refreshService.triggerManualRefresh();
        return ResponseEntity.accepted().body(Map.of(
            "status", "refresh_triggered",
            "message", "Exchange rate delta refresh is running asynchronously",
            "previousRefreshTime", refreshService.getLastRefreshTime() != null
                ? refreshService.getLastRefreshTime().toString()
                : "never",
            "requestedAt", Instant.now().toString()
        ));
    }
}
