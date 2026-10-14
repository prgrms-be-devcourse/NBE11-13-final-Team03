package com.team3.backend.auth.cookie;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth-cookie")
public class AuthCookieProperties {

    private boolean secure;
    private String sameSite = "Lax";
    private String path = "/";
}