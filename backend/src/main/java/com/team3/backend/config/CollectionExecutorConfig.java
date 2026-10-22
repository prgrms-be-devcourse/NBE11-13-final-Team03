package com.team3.backend.config;

import com.team3.backend.global.role.ApiRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@ApiRole
public class CollectionExecutorConfig {
    @Bean("collectionExecutor")
    public ThreadPoolTaskExecutor collectionExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("manual-sync-");
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
