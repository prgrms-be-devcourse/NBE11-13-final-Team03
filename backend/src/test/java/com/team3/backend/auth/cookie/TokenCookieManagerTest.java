package com.team3.backend.auth.cookie;

import com.team3.backend.auth.jwt.JwtProperties;
import com.team3.backend.auth.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenCookieManagerTest {

    private TokenCookieManager tokenCookieManager;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenValidity(Duration.ofHours(2));
        jwtProperties.setRefreshTokenValidity(Duration.ofDays(7));

        AuthCookieProperties cookieProperties =
                new AuthCookieProperties();

        cookieProperties.setSecure(true);
        cookieProperties.setSameSite("Lax");
        cookieProperties.setPath("/");

        tokenCookieManager = new TokenCookieManager(
                jwtProperties,
                cookieProperties
        );
    }

    @Test
    @DisplayName("Access Token과 Refresh Token에 동일한 보안 설정을 적용한다")
    void addsTokenCookiesWithCommonSecurityOptions() {
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        TokenService.TokenPair tokenPair =
                new TokenService.TokenPair(
                        "access-value",
                        "refresh-value"
                );

        tokenCookieManager.addTokenCookies(response, tokenPair);

        List<String> cookies =
                response.getHeaders(HttpHeaders.SET_COOKIE);

        assertEquals(2, cookies.size());

        assertTrue(cookies.stream().anyMatch(cookie ->
                cookie.contains("access_token=access-value")
                        && cookie.contains("HttpOnly")
                        && cookie.contains("Secure")
                        && cookie.contains("SameSite=Lax")
                        && cookie.contains("Path=/")
        ));

        assertTrue(cookies.stream().anyMatch(cookie ->
                cookie.contains("refresh_token=refresh-value")
                        && cookie.contains("HttpOnly")
                        && cookie.contains("Secure")
                        && cookie.contains("SameSite=Lax")
                        && cookie.contains("Path=/")
        ));
    }

    @Test
    @DisplayName("로그아웃 시 두 토큰 쿠키의 유효 시간을 0으로 설정한다")
    void deletesTokenCookies() {
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        tokenCookieManager.deleteTokenCookies(response);

        List<String> cookies =
                response.getHeaders(HttpHeaders.SET_COOKIE);

        assertEquals(2, cookies.size());

        assertTrue(cookies.stream().anyMatch(cookie ->
                cookie.contains("access_token=")
                        && cookie.contains("Max-Age=0")
        ));

        assertTrue(cookies.stream().anyMatch(cookie ->
                cookie.contains("refresh_token=")
                        && cookie.contains("Max-Age=0")
        ));
    }
}