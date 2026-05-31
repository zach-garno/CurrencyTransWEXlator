package com.wex.currencytranswexlator.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures async executor for @Async methods (rate refresh jobs).
 *
 * @EnableAsync is required here - without it, @Async annotations are
 * silently ignored and no proxy is created, which can cause context
 * startup errors when AsyncConfigurer is implemented.
 *
 * Virtual threads (Project Loom) are enabled by default for the web
 * request layer via spring.threads.virtual.enabled in Spring Boot 3.2.
 * This pool is for background tasks only - intentionally kept small.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "wexTaskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("wex-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor();
    }
}
