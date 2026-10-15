package com.team3.backend.auth.oauth2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KakaoUserInfoTest {

    @Test
    @DisplayName("카카오 이메일 값이 null이면 문자열 null이 아니라 실제 null을 반환한다")
    void emailReturnsNullWhenEmailValueIsNull() {
        Map<String, Object> account = new HashMap<>();
        account.put("email", null);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", 12345L);
        attributes.put("kakao_account", account);

        KakaoUserInfo userInfo = new KakaoUserInfo(attributes);

        assertNull(userInfo.email());
    }

    @Test
    @DisplayName("카카오 계정 정보가 없으면 이메일은 null이다")
    void emailReturnsNullWhenKakaoAccountIsMissing() {
        KakaoUserInfo userInfo = new KakaoUserInfo(
                Map.of("id", 12345L)
        );

        assertNull(userInfo.email());
    }

    @Test
    @DisplayName("카카오 이메일의 앞뒤 공백을 제거한다")
    void emailReturnsTrimmedValue() {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "email", " user@example.com "
                )
        );

        KakaoUserInfo userInfo = new KakaoUserInfo(attributes);

        assertEquals("user@example.com", userInfo.email());
    }

    @Test
    @DisplayName("카카오 ID가 숫자 문자열이면 Long으로 변환한다")
    void convertsStringIdToLong() {
        KakaoUserInfo userInfo = new KakaoUserInfo(
                Map.of("id", "12345")
        );

        assertEquals(12345L, userInfo.id());
    }

    @Test
    @DisplayName("카카오 ID가 올바른 숫자가 아니면 null을 반환한다")
    void returnsNullForInvalidId() {
        KakaoUserInfo userInfo = new KakaoUserInfo(
                Map.of("id", "invalid")
        );

        assertNull(userInfo.id());
    }
}