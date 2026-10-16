package com.team3.backend.auth.oauth2;

import java.util.Map;

public record KakaoUserInfo(
        Map<String, Object> attributes
) implements OAuth2UserInfo {

    @Override
    public Long id() {
        Object value = attributes.get("id");

        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value instanceof String stringValue
                && !stringValue.isBlank()) {
            try {
                return Long.valueOf(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    @Override
    public String email() {
        Map<String, Object> account = kakaoAccount();

        return account == null
                ? null
                : nullableString(account.get("email"));
    }

    @Override
    public String name() {
        Map<String, Object> profile = profile();

        return profile == null
                ? null
                : nullableString(profile.get("nickname"));
    }

    @Override
    public String imageUrl() {
        Map<String, Object> profile = profile();

        return profile == null
                ? null
                : nullableString(profile.get("profile_image_url"));
    }

    private Map<String, Object> kakaoAccount() {
        return mapValue(attributes.get("kakao_account"));
    }

    private Map<String, Object> profile() {
        Map<String, Object> account = kakaoAccount();

        return account == null
                ? null
                : mapValue(account.get("profile"));
    }

    private String nullableString(Object value) {
        if (!(value instanceof String stringValue)) {
            return null;
        }

        String trimmedValue = stringValue.trim();

        return trimmedValue.isEmpty()
                ? null
                : trimmedValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }

        return (Map<String, Object>) map;
    }
}