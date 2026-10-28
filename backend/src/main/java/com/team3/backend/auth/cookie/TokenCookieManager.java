package com.team3.backend.auth.cookie;

import com.team3.backend.auth.jwt.JwtProperties;
import com.team3.backend.auth.service.TokenService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class TokenCookieManager {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final JwtProperties jwtProperties;
    private final AuthCookieProperties cookieProperties;

    public void addTokenCookies(
            HttpServletResponse response,
            TokenService.TokenPair tokenPair
    ) {
        addCookie(
                response,
                ACCESS_TOKEN_COOKIE,
                tokenPair.accessToken(),
                jwtProperties.getAccessTokenValidity()
        );

        addCookie(
                response,
                REFRESH_TOKEN_COOKIE,
                tokenPair.refreshToken(),
                jwtProperties.getRefreshTokenValidity()
        );
    }

    public void deleteTokenCookies(HttpServletResponse response) {
        deleteCookie(response, ACCESS_TOKEN_COOKIE);
        deleteCookie(response, REFRESH_TOKEN_COOKIE);
    }

    private void addCookie(
            HttpServletResponse response,
            String name,
            String value,
            Duration maxAge
    ) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .sameSite(cookieProperties.getSameSite())
                .path(cookieProperties.getPath())
                .maxAge(maxAge)
                .build();

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie.toString()
        );
    }

    private void deleteCookie(
            HttpServletResponse response,
            String name
    ) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .sameSite(cookieProperties.getSameSite())
                .path(cookieProperties.getPath())
                .maxAge(Duration.ZERO)
                .build();

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie.toString()
        );
    }
}