package com.team3.backend.auth.oauth2;

import com.team3.backend.user.domain.entity.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Getter
@RequiredArgsConstructor
public class CustomOAuth2User implements OAuth2User {
    private final User user;

    private final AuthProvider provider;

    private final OAuth2UserInfo userInfo;

    private final Map<String, Object> attributes;

    private final String nameAttributeKey;

    public static CustomOAuth2User unregistered(AuthProvider provider, OAuth2UserInfo userInfo, Map<String, Object> attributes, String nameAttributeKey) {
        return new CustomOAuth2User(null, provider, userInfo, attributes, nameAttributeKey);
    }

    public boolean isRegistered() {
        return user != null;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {

        if ( user == null ) {
            return List.of(new SimpleGrantedAuthority("USER"));
        }

        return List.of(new SimpleGrantedAuthority(user.getRole().name()));
    }

    @Override
    public String getName() {
        return String.valueOf(attributes.get(nameAttributeKey));
    }
}
