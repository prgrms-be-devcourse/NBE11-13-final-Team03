package com.team3.backend.auth.unlink;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KakaoUnlinkProperties.class)
public class KakaoUnlinkConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.kakao-unlink.enabled", havingValue = "true")
    HttpClient kakaoUnlinkHttpClient(KakaoUnlinkProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Bean
    @ConditionalOnProperty(name = "app.kakao-unlink.enabled", havingValue = "true")
    KakaoUnlinkClient kakaoUnlinkClient(HttpClient kakaoUnlinkHttpClient, KakaoUnlinkProperties properties, ObjectMapper mapper) {
        return new KakaoUnlinkClient(kakaoUnlinkHttpClient, properties, mapper);
    }
    @Bean
    @ConditionalOnProperty(name = "app.kakao-unlink.enabled", havingValue = "true")
    KakaoUnlinkProcessor kakaoUnlinkProcessor(JdbcTemplate jdbc, KakaoUnlinkClient client, KakaoUnlinkProperties properties, Clock clock,
            PlatformTransactionManager transactions) {
        return new KakaoUnlinkProcessor(jdbc, client, properties, clock, transactions);
    }
    @Bean
    @ConditionalOnProperty(name = "app.kakao-unlink.enabled", havingValue = "true")
    KakaoUnlinkWorker kakaoUnlinkWorker(KakaoUnlinkProcessor processor, KakaoUnlinkProperties properties) {
        return new KakaoUnlinkWorker(processor, properties);
    }
    @Bean
    @ConditionalOnProperty(name = "app.kakao-unlink.enabled", havingValue = "true")
    ThreadPoolTaskScheduler kakaoUnlinkScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("kakao-unlink-");
        return scheduler;
    }
}
