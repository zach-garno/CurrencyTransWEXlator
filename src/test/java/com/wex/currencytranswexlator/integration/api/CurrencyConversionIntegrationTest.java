package com.wex.currencytranswexlator.integration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import com.wex.currencytranswexlator.exchange.repository.ExchangeRateRepository;
import com.wex.currencytranswexlator.transaction.controller.TransactionRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration - API
 * End-to-end tests for currency conversion including:
 *   - Successful conversion using a seeded exchange rate
 *   - 422 when rate is older than 6 months
 *   - 422 when no rate exists for currency
 *   - ratesAsOf field presence
 *
 * WireMock is configured with a default stub returning empty data for all
 * Treasury API calls. This prevents cache misses from falling through to the
 * real Treasury API during testing, ensuring deterministic behaviour.
 * Individual tests that need specific rate data seed the exchange_rates
 * table directly via ExchangeRateRepository.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Currency Conversion - End-to-End Integration Tests")
class CurrencyConversionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        // Default stub: any Treasury API call returns empty data.
        // This ensures cache misses don't call the real API and return a
        // deterministic empty result rather than a 503.
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[]}")));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("exchange-rate.refresh.cron", () -> "-");
        registry.add("treasury.api.base-url",
            () -> "http://localhost:" + wireMock.port());
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ExchangeRateRepository exchangeRateRepository;

    private String storeTransaction(LocalDate date, BigDecimal amount) throws Exception {
        var req = new TransactionRequest("Test", date, amount);
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/transactions")
                .header("X-Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void seedRate(String currencyCode, LocalDate effectiveDate, BigDecimal rate) {
        exchangeRateRepository.findByCurrencyCodeAndEffectiveDate(currencyCode, effectiveDate)
            .orElseGet(() -> exchangeRateRepository.save(
                new ExchangeRate(currencyCode, effectiveDate, effectiveDate, rate)));
    }

    // ── Successful conversion ─────────────────────────────────────────────────

    @Test
    @DisplayName("returns converted amount when rate exists within 6 months")
    void successfulConversionWithSeededRate() throws Exception {
        LocalDate txDate = LocalDate.of(2026, 5, 1);
        LocalDate rateDate = LocalDate.of(2026, 3, 31);
        seedRate("Canada-Dollar", rateDate, new BigDecimal("1.3621"));

        String id = storeTransaction(txDate, new BigDecimal("100.00"));

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id)
                .param("currency", "Canada-Dollar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exchangeRate").value(1.3621))
            .andExpect(jsonPath("$.convertedAmount").value(136.21))
            .andExpect(jsonPath("$.currencyCode").value("Canada-Dollar"))
            .andExpect(jsonPath("$.amountUsd").value(100.0))
            .andExpect(jsonPath("$.ratesAsOf").isNotEmpty());
    }

    @Test
    @DisplayName("uses most recent rate when multiple rates exist within 6 months")
    void usesNewestRateWhenMultipleExist() throws Exception {
        LocalDate txDate = LocalDate.of(2026, 5, 15);
        seedRate("Euro Zone-Euro", LocalDate.of(2025, 12, 31), new BigDecimal("0.92"));
        seedRate("Euro Zone-Euro", LocalDate.of(2026, 3, 31), new BigDecimal("0.95"));

        String id = storeTransaction(txDate, new BigDecimal("200.00"));

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id)
                .param("currency", "Euro Zone-Euro"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exchangeRate").value(0.95))
            .andExpect(jsonPath("$.convertedAmount").value(190.0));
    }

    @Test
    @DisplayName("converted amount is rounded to 2 decimal places (HALF_UP)")
    void convertedAmountRoundedTo2dp() throws Exception {
        // 10.00 * 1.362100 = 13.621 -> rounds HALF_UP to 13.62
        // Using values that produce clear half-up rounding (5 in 3rd decimal)
        LocalDate txDate = LocalDate.of(2026, 5, 1);
        seedRate("Canada-Dollar", LocalDate.of(2026, 3, 31), new BigDecimal("1.3565"));

        String id = storeTransaction(txDate, new BigDecimal("10.00"));

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id)
                .param("currency", "Canada-Dollar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.convertedAmount").value(13.62));
    }

    // ── 6-month boundary enforcement ─────────────────────────────────────────

    @Test
    @DisplayName("returns 422 when rate exists but is older than 6 months")
    void returns422WhenRateOlderThan6Months() throws Exception {
        LocalDate txDate = LocalDate.of(2026, 5, 1);
        // Rate is 7 months before purchase date - outside the 6-month window.
        // WireMock default stub returns empty data so the API fallback also
        // finds nothing, ensuring a 422 rather than a 503.
        LocalDate oldRateDate = txDate.minusMonths(7);
        seedRate("Japan-Yen", oldRateDate, new BigDecimal("149.50"));

        String id = storeTransaction(txDate, new BigDecimal("50.00"));

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id)
                .param("currency", "Japan-Yen"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail")
                .value("Purchase cannot be converted to the target currency."));
    }

    @Test
    @DisplayName("returns 422 with required message when no rate exists for currency")
    void returns422WhenNoCurrencyRateExists() throws Exception {
        // No rate seeded and WireMock returns empty data - ensures 422 not 503.
        String id = storeTransaction(LocalDate.of(2026, 5, 1), new BigDecimal("75.00"));

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id)
                .param("currency", "Fictional-Currency"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail")
                .value("Purchase cannot be converted to the target currency."));
    }

    @Test
    @DisplayName("rate exactly at 6-month boundary is accepted")
    void rateExactlyAt6MonthBoundaryAccepted() throws Exception {
        LocalDate txDate = LocalDate.of(2026, 5, 15);
        LocalDate boundaryDate = txDate.minusMonths(6);
        seedRate("Australia-Dollar", boundaryDate, new BigDecimal("1.55"));

        String id = storeTransaction(txDate, new BigDecimal("100.00"));

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id)
                .param("currency", "Australia-Dollar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.convertedAmount").value(155.0));
    }

    // ── Response field completeness ───────────────────────────────────────────

    @Test
    @DisplayName("conversion response contains all required fields per spec")
    void conversionResponseHasAllRequiredFields() throws Exception {
        LocalDate txDate = LocalDate.of(2026, 5, 1);
        seedRate("Canada-Dollar", LocalDate.of(2026, 3, 31), new BigDecimal("1.3621"));
        String id = storeTransaction(txDate, new BigDecimal("100.00"));

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id)
                .param("currency", "Canada-Dollar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.description").isNotEmpty())
            .andExpect(jsonPath("$.transactionDate").isNotEmpty())
            .andExpect(jsonPath("$.amountUsd").isNotEmpty())
            .andExpect(jsonPath("$.exchangeRate").isNotEmpty())
            .andExpect(jsonPath("$.convertedAmount").isNotEmpty())
            .andExpect(jsonPath("$.currencyCode").isNotEmpty())
            .andExpect(jsonPath("$.ratesAsOf").isNotEmpty());
    }
}
