package com.team3.backend.auth.cookie;

import com.team3.backend.auth.session.AuthSessionProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DeviceCookieManager {

    private static final String DEVICE_COOKIE_NAME = "device_id";

    private final AuthCookieProperties cookieProperties;
    private final AuthSessionProperties sessionProperties;

    public String resolveOrCreate(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String deviceId = resolve(request);

        if (deviceId != null) {
            return deviceId;
        }

        String newDeviceId = UUID.randomUUID().toString();

        ResponseCookie cookie = ResponseCookie
                .from(DEVICE_COOKIE_NAME, newDeviceId)
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .sameSite(cookieProperties.getSameSite())
                .path(cookieProperties.getPath())
                .maxAge(sessionProperties.getDeviceCookieValidity())
                .build();

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie.toString()
        );

        return newDeviceId;
    }

    private String resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (!DEVICE_COOKIE_NAME.equals(cookie.getName())) {
                continue;
            }

            String value = cookie.getValue();

            if (value == null || value.isBlank()) {
                return null;
            }

            try {
                return UUID.fromString(value).toString();
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        return null;
    }
}