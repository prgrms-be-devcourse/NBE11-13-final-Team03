package com.team3.backend.auth.service;

import com.team3.backend.auth.oauth2.AuthProvider;
import com.team3.backend.auth.oauth2.CustomOAuth2User;
import com.team3.backend.auth.oauth2.OAuth2UserInfo;
import com.team3.backend.auth.oauth2.OAuth2UserInfoFactory;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    @Value("${app.admin-kakao-ids:}")
    private String adminKakaoIds;

    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest)
            throws OAuth2AuthenticationException {

        OAuth2User oAuth2User = super.loadUser(userRequest);

        String registrationId =
                userRequest.getClientRegistration().getRegistrationId();

        String nameAttributeKey =
                userRequest.getClientRegistration()
                        .getProviderDetails()
                        .getUserInfoEndpoint()
                        .getUserNameAttributeName();

        AuthProvider provider =
                AuthProvider.from(registrationId);

        OAuth2UserInfo userInfo =
                OAuth2UserInfoFactory.of(
                        provider,
                        oAuth2User.getAttributes()
                );

        if (userInfo.email() == null) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_required"),
                    "SNS 계정에서 이메일을 가져오지 못했습니다. 이메일 제공 동의가 필요합니다."
            );
        }

        User user = userRepository
                .findByKakaoIdAndProvider(userInfo.id(), provider)
                .map(existing -> {
                    existing.updateProfile(userInfo.name());

                    if (isAdmin(userInfo.id()) && existing.getRole() != Role.ADMIN) {
                        existing.promoteToAdmin();
                    }

                    return existing;
                })
                .orElseGet(() -> {

                    User newUser = User.builder()
                            .kakaoId(userInfo.id())
                            .nickname(userInfo.name())
                            .email(userInfo.email())
                            .role(isAdmin(userInfo.id()) ? Role.ADMIN : Role.USER)
                            .provider(provider)
                            .build();

                    return userRepository.save(newUser);
                });

        return new CustomOAuth2User(
                user,
                provider,
                userInfo,
                oAuth2User.getAttributes(),
                nameAttributeKey
        );
    }

    private boolean isAdmin(Long kakaoId) {
        if (adminKakaoIds == null || adminKakaoIds.isBlank()) {
            return false;
        }

        Set<Long> adminIds = Arrays.stream(adminKakaoIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        return adminIds.contains(kakaoId);
    }
}