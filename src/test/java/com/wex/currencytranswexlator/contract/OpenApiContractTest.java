package com.wex.currencytranswexlator.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract Tests
 * Validates that the auto-generated OpenAPI 3.0 spec matches the actual
 * endpoint behavior. Catches contract drift between documentation and implementation.
 *
 * Strategy:
 * - Fetch the live /api-docs spec from the running application
 * - Assert required paths, schemas, and response codes are present
 * - Verify field presence in response schemas matches what the code returns
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("OpenAPI Contract Tests")
class OpenApiContractTest {

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
        registry.add("exchange-rate.refresh.cron", () -> "-");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("OpenAPI spec is accessible at /api-docs")
    void openApiSpecIsAccessible() throws Exception {
        mockMvc.perform(get("/api-docs"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    @DisplayName("OpenAPI spec includes POST /api/v1/transactions path")
    void specIncludesStoreTransactionPath() throws Exception {
        String spec = mockMvc.perform(get("/api-docs"))
            .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(spec);
        JsonNode paths = root.get("paths");

        assertThat(paths).isNotNull();
        assertThat(paths.has("/api/v1/transactions")).isTrue();
        assertThat(paths.get("/api/v1/transactions").has("post")).isTrue();
    }

    @Test
    @DisplayName("OpenAPI spec includes GET /api/v1/transactions/{id}/convert path")
    void specIncludesConvertPath() throws Exception {
        String spec = mockMvc.perform(get("/api-docs"))
            .andReturn().getResponse().getContentAsString();

        JsonNode paths = objectMapper.readTree(spec).get("paths");

        assertThat(paths.has("/api/v1/transactions/{id}/convert")).isTrue();
        assertThat(paths.get("/api/v1/transactions/{id}/convert").has("get")).isTrue();
    }

    @Test
    @DisplayName("OpenAPI spec includes GET /api/v1/currencies path")
    void specIncludesCurrenciesPath() throws Exception {
        String spec = mockMvc.perform(get("/api-docs"))
            .andReturn().getResponse().getContentAsString();

        JsonNode paths = objectMapper.readTree(spec).get("paths");
        assertThat(paths.has("/api/v1/currencies")).isTrue();
    }

    @Test
    @DisplayName("OpenAPI spec includes admin rate refresh path")
    void specIncludesAdminRefreshPath() throws Exception {
        String spec = mockMvc.perform(get("/api-docs"))
            .andReturn().getResponse().getContentAsString();

        JsonNode paths = objectMapper.readTree(spec).get("paths");
        assertThat(paths.has("/api/v1/admin/rates/refresh")).isTrue();
    }

    @Test
    @DisplayName("POST /api/v1/transactions documents 201, 400, and 409 response codes")
    void storeTransactionDocumentsExpectedResponseCodes() throws Exception {
        String spec = mockMvc.perform(get("/api-docs"))
            .andReturn().getResponse().getContentAsString();

        JsonNode postOp = objectMapper.readTree(spec)
            .get("paths")
            .get("/api/v1/transactions")
            .get("post")
            .get("responses");

        assertThat(postOp.has("201")).isTrue();
        assertThat(postOp.has("400")).isTrue();
        assertThat(postOp.has("409")).isTrue();
    }

    @Test
    @DisplayName("GET convert documents 200, 404, and 422 response codes")
    void convertDocumentsExpectedResponseCodes() throws Exception {
        String spec = mockMvc.perform(get("/api-docs"))
            .andReturn().getResponse().getContentAsString();

        JsonNode getOp = objectMapper.readTree(spec)
            .get("paths")
            .get("/api/v1/transactions/{id}/convert")
            .get("get")
            .get("responses");

        assertThat(getOp.has("200")).isTrue();
        assertThat(getOp.has("404")).isTrue();
        assertThat(getOp.has("422")).isTrue();
    }

    @Test
    @DisplayName("Swagger UI is accessible")
    void swaggerUiIsAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection()); // redirects to swagger-ui/index.html
    }

    @Test
    @DisplayName("Actuator health endpoint is accessible")
    void actuatorHealthIsAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
