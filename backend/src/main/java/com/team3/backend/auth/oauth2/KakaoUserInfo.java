package com.team3.backend.auth.oauth2;

import java.util.Map;

public record KakaoUserInfo(
        Map<String, Object> attributes
) implements OAuth2UserInfo {

    @Override
    public Long id() {
        Object id = attributes.get("id");
        return id == null ? null : Long.valueOf(id.toString());
    }

    @Override
    public String email() {
        Map<String, Object> kakaAccount = kakaAccount();
        return kakaAccount == null ? null : String.valueOf( kakaAccount.get("email") );
    }

    @Override
    public String name() {
        Map<String, Object> profile = profile();
        return profile == null ? null : String.valueOf( profile.get("nickname") );
    }

    @Override
    public String imageUrl() {
        Map<String, Object> profile = profile();
        return profile == null ? null : String.valueOf( profile.get("profile_image_url") );
    }

    private Map<String, Object> kakaAccount() {
        return (Map<String, Object>) attributes.get("kakao_account");
    }

    private Map<String, Object> profile() {
        return (Map<String, Object>) kakaAccount().get("profile");
    }

}