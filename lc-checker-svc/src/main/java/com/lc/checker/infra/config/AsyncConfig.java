package com.lc.checker.infra.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor backing {@code @Async} pipeline kickoffs from the streaming controller.
 * Sized for an interactive demo: each lc-check run is I/O-bound (extractor HTTP +
 * LLM HTTP) so we prefer a small ready pool with modest queueing.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "lcCheckExecutor")
    public Executor lcCheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("lc-check-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
