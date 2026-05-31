package com.wex.currencytranswexlator.common.config;

import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient configuration for external API calls (Treasury API).
 *
 * Timeouts here are a belt-and-suspenders addition to the Resilience4j
 * TimeLimiter configured in application.yml. The TimeLimiter operates at
 * the reactive stream level; these Netty-level timeouts catch lower-level
 * socket hangs that the TimeLimiter may not see.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
            .responseTimeout(Duration.ofSeconds(5))
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS)));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
