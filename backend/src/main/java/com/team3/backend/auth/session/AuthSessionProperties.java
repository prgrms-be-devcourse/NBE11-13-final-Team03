package com.team3.backend.auth.session;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth-session")
public class AuthSessionProperties {

    private int maxActiveSessions = 3;
    private Duration deviceCookieValidity = Duration.ofDays(365);
}