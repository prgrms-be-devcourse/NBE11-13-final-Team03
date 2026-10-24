package com.team3.backend.client.config;

import feign.Logger;
import feign.Client;
import feign.http2client.Http2Client;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpClient;
import java.time.Duration;

/** Feign child context only; never annotate as a root configuration. */
public class NotificationUserFeignConfig {
    @Bean(destroyMethod = "close")
    HttpClient notificationUserHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    @Bean Client notificationUserTransport(HttpClient notificationUserHttpClient) {
        return new Http2Client(notificationUserHttpClient);
    }
    @Bean Request.Options notificationUserOptions() {
        return new Request.Options(1, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, false);
    }
    @Bean Retryer notificationUserRetryer() { return Retryer.NEVER_RETRY; }
    @Bean Logger.Level notificationUserLogLevel() { return Logger.Level.NONE; }
    @Bean RequestInterceptor notificationUserHeaders(NotificationFacadeProperties properties) {
        return template -> {
            template.header("X-INTERNAL-KEY", properties.apiKey());
            template.header("X-Correlation-ID", UUID.randomUUID().toString());
        };
    }
}
