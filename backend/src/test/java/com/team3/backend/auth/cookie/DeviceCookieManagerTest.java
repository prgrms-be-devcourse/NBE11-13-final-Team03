package com.team3.backend.auth.cookie;

import com.team3.backend.auth.session.AuthSessionProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeviceCookieManagerTest {

    private DeviceCookieManager deviceCookieManager;

    @BeforeEach
    void setUp() {
        AuthCookieProperties cookieProperties =
                new AuthCookieProperties();

        cookieProperties.setSecure(true);
        cookieProperties.setSameSite("Lax");
        cookieProperties.setPath("/");

        AuthSessionProperties sessionProperties =
                new AuthSessionProperties();

        sessionProperties.setDeviceCookieValidity(
                Duration.ofDays(365)
        );

        deviceCookieManager = new DeviceCookieManager(
                cookieProperties,
                sessionProperties
        );
    }

    @Test
    @DisplayName("정상적인 device_id UUID 쿠키가 있으면 기존 값을 사용한다")
    void usesExistingValidDeviceId() {
        String existingDeviceId =
                UUID.randomUUID().toString();

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setCookies(
                new Cookie(
                        "device_id",
                        existingDeviceId
                )
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String result =
                deviceCookieManager.resolveOrCreate(
                        request,
                        response
                );

        assertEquals(existingDeviceId, result);

        assertNull(
                response.getHeader(HttpHeaders.SET_COOKIE),
                "정상 쿠키가 있으면 새 쿠키를 발급하지 않아야 합니다."
        );
    }

    @Test
    @DisplayName("device_id 쿠키가 없으면 새로운 UUID 쿠키를 발급한다")
    void createsDeviceIdWhenCookieIsMissing() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String result =
                deviceCookieManager.resolveOrCreate(
                        request,
                        response
                );

        assertDoesNotThrow(() ->
                UUID.fromString(result)
        );

        String setCookie =
                response.getHeader(HttpHeaders.SET_COOKIE);

        assertNotNull(setCookie);
        assertTrue(setCookie.contains("device_id=" + result));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertTrue(setCookie.contains("Path=/"));
        assertTrue(setCookie.contains("Max-Age=31536000"));
    }

    @Test
    @DisplayName("device_id 쿠키가 UUID 형식이 아니면 새로운 UUID로 교체한다")
    void replacesInvalidDeviceId() {
        String invalidDeviceId =
                "invalid-device-id-that-is-not-a-uuid";

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setCookies(
                new Cookie(
                        "device_id",
                        invalidDeviceId
                )
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String result =
                deviceCookieManager.resolveOrCreate(
                        request,
                        response
                );

        assertNotEquals(invalidDeviceId, result);

        assertDoesNotThrow(() ->
                UUID.fromString(result)
        );

        String setCookie =
                response.getHeader(HttpHeaders.SET_COOKIE);

        assertNotNull(setCookie);
        assertTrue(setCookie.contains("device_id=" + result));
        assertFalse(setCookie.contains(invalidDeviceId));
    }

    @Test
    @DisplayName("device_id 쿠키가 비어 있으면 새로운 UUID로 교체한다")
    void replacesBlankDeviceId() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setCookies(
                new Cookie("device_id", "")
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String result =
                deviceCookieManager.resolveOrCreate(
                        request,
                        response
                );

        assertDoesNotThrow(() ->
                UUID.fromString(result)
        );

        assertNotNull(
                response.getHeader(HttpHeaders.SET_COOKIE)
        );
    }
}