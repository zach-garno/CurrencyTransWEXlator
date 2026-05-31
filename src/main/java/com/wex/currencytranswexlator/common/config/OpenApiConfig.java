package com.wex.currencytranswexlator.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("CurrencyTransWEXlator API")
                .description("""
                    Purchase transaction storage and currency conversion service.
                    
                    Stores USD purchase transactions and retrieves them converted to any currency 
                    supported by the U.S. Treasury Reporting Rates of Exchange API.
                    
                    Exchange rates are pre-loaded on startup and refreshed every 24 hours via 
                    delta polling. The `ratesAsOf` field on conversion responses reflects the 
                    last successful refresh timestamp.
                    
                    **Idempotency:** POST /transactions requires an `X-Idempotency-Key` header 
                    (UUID). Duplicate submissions within 24 hours return the original response.
                    """)
                .version("0.1.0")
                .contact(new Contact()
                    .name("Zach Garno")
                    .url("https://linkedin.com/in/zachgarno")))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("Local development")));
    }
}
