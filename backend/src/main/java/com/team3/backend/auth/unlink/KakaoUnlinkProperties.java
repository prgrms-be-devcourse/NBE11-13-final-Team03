package com.team3.backend.auth.unlink;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.kakao-unlink")
public record KakaoUnlinkProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String adminKey, @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT1M") Duration retryDelay, @DefaultValue("PT5S") Duration timeout,
        @DefaultValue("10") int batchSize) {
    public KakaoUnlinkProperties {
        if (maxAttempts < 1 || maxAttempts > 20 || batchSize < 1 || batchSize > 100
                || retryDelay == null || retryDelay.toMillis() < 1000
                || timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid Kakao unlink settings");
        }
        if (enabled && (adminKey == null || adminKey.isBlank())) {
            throw new IllegalArgumentException("Kakao unlink requires a server admin key");
        }
    }
    @Override public String toString() { return "KakaoUnlinkProperties[enabled=" + enabled + ", adminKey=REDACTED]"; }
}
