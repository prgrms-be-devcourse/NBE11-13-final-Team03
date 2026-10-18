package com.team3.backend.auth.oauth2;

import java.util.Map;

public class OAuth2UserInfoFactory {
    private OAuth2UserInfoFactory() {}

    public static OAuth2UserInfo of (AuthProvider provider, Map<String, Object> attributes) {
        return switch (provider) {
            case KAKAO -> new KakaoUserInfo(attributes);
        };
    }
}
