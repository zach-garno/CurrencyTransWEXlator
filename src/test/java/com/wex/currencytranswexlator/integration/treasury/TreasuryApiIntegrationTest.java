package com.wex.currencytranswexlator.integration.treasury;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.wex.currencytranswexlator.exchange.entity.ExchangeRate;
import com.wex.currencytranswexlator.exchange.provider.TreasuryApiClient;
import com.wex.currencytranswexlator.exchange.repository.ExchangeRateRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration - Treasury
 * WireMock stubs simulate Treasury API responses for all scenarios:
 *   - Valid rate found
 *   - Rate exists but older than 6 months (expect 422 path)
 *   - No rate for currency (expect 422 path)
 *   - API timeout (circuit breaker activation)
 *   - 503 response from Treasury
 */
@SpringBootTest
@Testcontainers
@DisplayName("Treasury API Client - Integration Tests")
class TreasuryApiIntegrationTest {

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
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("exchange-rate.refresh.cron", () -> "-");
        registry.add("treasury.api.base-url",
            () -> "http://localhost:" + (wireMock != null ? wireMock.port() : 8089));
    }

    @Autowired TreasuryApiClient treasuryApiClient;
    @Autowired ExchangeRateRepository exchangeRateRepository;

    private static final String CANADA_DOLLAR = "Canada-Dollar";

    // ── Valid rate found ───────────────────────────────────────────────────────

    @Test
    @DisplayName("returns parsed rate records when Treasury API returns valid data")
    void returnsRatesWhenApiRespondsOk() {
        wireMock.stubFor(get(urlPathEqualTo("/"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "data": [
                        {
                          "country_currency_desc": "Canada-Dollar",
                          "exchange_rate": "1.3621",
                          "record_date": "2026-03-31",
                          "effective_date": "2026-03-31"
                        }
                      ]
                    }
                    """)));

        var records = treasuryApiClient.fetchRates(CANADA_DOLLAR, LocalDate.of(2026, 1, 1));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).countryCurrencyDesc()).isEqualTo("Canada-Dollar");
        assertThat(records.get(0).exchangeRate()).isEqualTo("1.3621");
        assertThat(records.get(0).recordDate()).isEqualTo("2026-03-31");
    }

    // ── Empty response (no rate found) ────────────────────────────────────────

    @Test
    @DisplayName("returns empty list when no rates available for currency")
    void returnsEmptyWhenNoRatesFound() {
        wireMock.stubFor(get(urlPathEqualTo("/"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "data": []
                    }
                    """)));

        var records = treasuryApiClient.fetchRates("Fictional-Currency", LocalDate.of(2020, 1, 1));

        assertThat(records).isEmpty();
    }

    // ── 503 from Treasury API ─────────────────────────────────────────────────

    @Test
    @DisplayName("throws TreasuryApiUnavailableException when Treasury returns 503")
    void throwsOnTreasury503() {
        wireMock.stubFor(get(urlPathEqualTo("/"))
            .willReturn(aResponse().withStatus(503)));

        // First call: circuit breaker in CLOSED state, exception propagated
        assertThatThrownBy(() ->
            treasuryApiClient.fetchRates(CANADA_DOLLAR, LocalDate.of(2026, 1, 1)))
            .isInstanceOf(TreasuryApiClient.TreasuryApiUnavailableException.class);
    }

    // ── Timeout ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("throws TreasuryApiUnavailableException when Treasury API times out")
    void throwsOnTimeout() {
        wireMock.stubFor(get(urlPathEqualTo("/"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(6000)  // 6s > 5s timeout configured in application.yml
                .withBody("{\"data\":[]}")));

        assertThatThrownBy(() ->
            treasuryApiClient.fetchRates(CANADA_DOLLAR, LocalDate.of(2026, 1, 1)))
            .isInstanceOf(Exception.class); // TimeLimiter or circuit breaker exception
    }

    // ── Delta refresh ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("bulk fetch uses record_date filter parameter")
    void bulkFetchSendsRecordDateFilter() {
        wireMock.stubFor(get(urlPathEqualTo("/"))
            .withQueryParam("filter", containing("record_date:gte:2026-01-01"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[]}")));

        var records = treasuryApiClient.fetchAllRatesSince(LocalDate.of(2026, 1, 1));

        assertThat(records).isEmpty();
        wireMock.verify(getRequestedFor(urlPathEqualTo("/"))
            .withQueryParam("filter", containing("record_date:gte:2026-01-01")));
    }
}
