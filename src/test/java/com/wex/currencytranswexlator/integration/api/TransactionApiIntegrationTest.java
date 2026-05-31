package com.wex.currencytranswexlator.integration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration - API
 * Full HTTP request/response cycle using a real PostgreSQL instance via Testcontainers.
 * Validates: field constraints, idempotency behavior, USD pass-through, error shapes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Transaction API - Integration Tests")
class TransactionApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("exchange-rate.refresh.cron", () -> "-");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final String POST_URL = "/api/v1/transactions";
    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    /**
     * Helper: store a transaction and return the response body as a String.
     * Named storeTransaction (not post) to avoid shadowing MockMvcRequestBuilders.post()
     * static import, which would cause compiler ambiguity on post(String) calls.
     */
    private String storeTransaction(String idempotencyKey, TransactionRequest req) throws Exception {
        return mockMvc.perform(post(POST_URL)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andReturn().getResponse().getContentAsString();
    }

    // ── Store transaction ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST valid transaction returns 201 with id and all fields")
    void postValidTransactionReturns201() throws Exception {
        var req = new TransactionRequest("Coffee purchase", LocalDate.of(2026, 3, 15), new BigDecimal("4.75"));

        mockMvc.perform(post(POST_URL)
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.description").value("Coffee purchase"))
            .andExpect(jsonPath("$.transactionDate").value("2026-03-15"))
            .andExpect(jsonPath("$.amountUsd").value(4.75))
            .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST missing idempotency key returns 400")
    void missingIdempotencyKeyReturns400() throws Exception {
        var req = new TransactionRequest("Test", LocalDate.now(), new BigDecimal("1.00"));
        mockMvc.perform(post(POST_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST description over 50 chars returns 400 with field error")
    void descriptionOver50CharsReturns400() throws Exception {
        var req = new TransactionRequest("A".repeat(51), LocalDate.now(), new BigDecimal("1.00"));
        mockMvc.perform(post(POST_URL)
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.properties.fieldErrors.description").isNotEmpty());
    }

    @Test
    @DisplayName("POST future transaction date returns 400 with field error")
    void futureDateReturns400() throws Exception {
        var req = new TransactionRequest("Test", LocalDate.now().plusDays(1), new BigDecimal("1.00"));
        mockMvc.perform(post(POST_URL)
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.properties.fieldErrors.transactionDate").isNotEmpty());
    }

    @Test
    @DisplayName("POST negative amount returns 400 with field error")
    void negativeAmountReturns400() throws Exception {
        var req = new TransactionRequest("Test", LocalDate.now(), new BigDecimal("-5.00"));
        mockMvc.perform(post(POST_URL)
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.properties.fieldErrors.amountUsd").isNotEmpty());
    }

    // ── Idempotency ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("duplicate POST with same key and payload returns original response with replay header")
    void duplicatePostReturnsCachedResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        var req = new TransactionRequest("Duplicate test", LocalDate.of(2026, 1, 10), new BigDecimal("25.00"));

        // First call
        String firstResponse = mockMvc.perform(post(POST_URL)
                .header(IDEMPOTENCY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Idempotency-Replayed", "false"))
            .andReturn().getResponse().getContentAsString();

        String firstId = objectMapper.readTree(firstResponse).get("id").asText();

        // Duplicate call
        mockMvc.perform(post(POST_URL)
                .header(IDEMPOTENCY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.id").value(firstId));
    }

    @Test
    @DisplayName("duplicate POST with same key but different payload returns 409")
    void sameKeyDifferentPayloadReturns409() throws Exception {
        String key = UUID.randomUUID().toString();
        var req1 = new TransactionRequest("Original", LocalDate.of(2026, 1, 10), new BigDecimal("10.00"));
        var req2 = new TransactionRequest("Modified", LocalDate.of(2026, 1, 10), new BigDecimal("10.00"));

        storeTransaction(key, req1); // first call succeeds

        mockMvc.perform(post(POST_URL)
                .header(IDEMPOTENCY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
            .andExpect(status().isConflict());
    }

    // ── Retrieve ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET unknown transaction id returns 404")
    void unknownIdReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/{id}/convert", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET with no currency returns USD pass-through")
    void noCurrencyReturnsUsdPassThrough() throws Exception {
        String key = UUID.randomUUID().toString();
        var req = new TransactionRequest("USD test", LocalDate.of(2026, 3, 15), new BigDecimal("100.00"));
        String postBody = storeTransaction(key, req);
        String id = objectMapper.readTree(postBody).get("id").asText();

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exchangeRate").value(1.0))
            .andExpect(jsonPath("$.convertedAmount").value(100.00))
            .andExpect(jsonPath("$.currencyCode").value("USD"));
    }

    @Test
    @DisplayName("GET with currency=USD returns pass-through")
    void usdCurrencyParamReturnsPassThrough() throws Exception {
        String key = UUID.randomUUID().toString();
        var req = new TransactionRequest("USD explicit", LocalDate.of(2026, 3, 15), new BigDecimal("50.00"));
        String postBody = storeTransaction(key, req);
        String id = objectMapper.readTree(postBody).get("id").asText();

        mockMvc.perform(get("/api/v1/transactions/{id}/convert", id)
                .param("currency", "USD"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exchangeRate").value(1.0))
            .andExpect(jsonPath("$.currencyCode").value("USD"));
    }
}
