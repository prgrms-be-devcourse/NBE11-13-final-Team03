package com.team3.backend.auth.oauth2;

// 현재는 카카오만 있으나 확장을 위해 추가
public enum AuthProvider {
    KAKAO;

    public static AuthProvider from(String registrationId) {
        return AuthProvider.valueOf(registrationId.toUpperCase());
    }
}
