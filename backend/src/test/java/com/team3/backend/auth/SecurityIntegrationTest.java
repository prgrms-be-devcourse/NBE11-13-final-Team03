package com.team3.backend.auth;

import com.team3.backend.auth.jwt.TokenProvider;
import com.team3.backend.auth.jwt.TokenType;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.auth.service.TokenService;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityIntegrationTest.TestEndpointController.class)
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired private TokenProvider tokens;
    @Autowired private TokenService tokenService;
    @Autowired private UserRepository users;

    private String accessToken(Duration validity) {
        return tokens.generateToken(User.builder().id(42L).role(Role.USER).build(),
                "test-session", validity, TokenType.ACCESS);
    }

    // csrf() replaces the filter's repository with a test repository. Use the real SPA cookie/header
    // flow so later bootstrap/refresh tests exercise the production CSRF configuration as well.
    private RequestPostProcessor csrfCookie() throws Exception {
        var cookie = mockMvc.perform(get("/api/interests/options")).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return request -> {
            var existing = request.getCookies();
            var cookies = new ArrayList<Cookie>();
            if (existing != null) cookies.addAll(Arrays.asList(existing));
            cookies.add(cookie);
            request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN", cookie.getValue());
            return request;
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/opportunities", "/api/opportunities/1", "/api/security-test/protected"})
    @DisplayName("공개·보호 API 모두 만료된 Access Token을 익명으로 처리하지 않는다")
    void expiredAccessTokenReturnsUnauthorized(String path) throws Exception {
        mockMvc.perform(get(path).cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(-1)))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_007"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-jwt", "broken.jwt.signature"})
    @DisplayName("공개 조회에 잘못된 Access Token을 보내면 401을 반환한다")
    void invalidAccessTokenReturnsUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/api/opportunities").cookie(new Cookie("access_token", token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_007"));
    }

    @Test
    void refreshTokenCannotAuthenticateAsAccessToken() throws Exception {
        var refresh = tokens.generateToken(User.builder().id(42L).role(Role.USER).build(),
                "test-session", Duration.ofMinutes(5), TokenType.REFRESH);
        mockMvc.perform(get("/api/opportunities").cookie(new Cookie("access_token", refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_007"));
    }

    @Test
    void validAccessTokenKeepsAuthenticatedPrincipal() throws Exception {
        mockMvc.perform(get("/api/security-test/identity")
                        .cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(5)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(42));
    }

    @Test
    void expiredAccessTokenDoesNotBlockCsrfBootstrap() throws Exception {
        mockMvc.perform(get("/api/interests/options")
                        .cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(-1)))))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void expiredAccessTokenDoesNotBlockOAuthLogin() throws Exception {
        var response = mockMvc.perform(get("/oauth2/authorization/kakao")
                        .cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(-1)))))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).startsWith("https://kauth.kakao.com/oauth/authorize");
    }

    @Test
    void expiredAccessTokenDoesNotReplaceInternalKeyAuthentication() throws Exception {
        mockMvc.perform(post("/api/internal/security-test")
                        .servletPath("/api/internal/security-test")
                        .cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(-1))))
                        .header("X-INTERNAL-KEY", "test-internal-api-key"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/internal/security-test")
                        .servletPath("/api/internal/security-test")
                        .cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(-1)))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_008"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "invalid", "absent"})
    @Transactional
    @DisplayName("Access Token 상태와 무관하게 유효한 Refresh Token으로 재발급하고 인증 요청을 재시도한다")
    void reissueRestoresAuthentication(String accessState) throws Exception {
        var user = users.saveAndFlush(User.builder().kakaoId(990001L).nickname("JWT test")
                .email("jwt-test@example.com").role(Role.USER).build());
        var pair = tokenService.issueToken(user, "jwt-test-device");
        var bootstrap = get("/api/interests/options");
        var request = post("/api/auth/reissue")
                .cookie(new Cookie("refresh_token", pair.refreshToken()));
        if (!accessState.equals("absent")) {
            var token = accessState.equals("expired")
                    ? tokens.generateToken(user, tokens.getSessionId(pair.accessToken()), Duration.ofMinutes(-1), TokenType.ACCESS)
                    : "invalid-access";
            request.cookie(new Cookie("access_token", token));
            bootstrap.cookie(new Cookie("access_token", token));
        }
        var csrfCookie = mockMvc.perform(bootstrap).andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        request.cookie(csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue());
        var response = mockMvc.perform(request).andExpect(status().isOk())
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().exists("refresh_token")).andReturn().getResponse();
        mockMvc.perform(get("/api/security-test/identity").cookie(response.getCookie("access_token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(user.getId().intValue()));
    }

    @Test
    void reissueWithoutRefreshTokenStillFailsWithRefreshError() throws Exception {
        mockMvc.perform(post("/api/auth/reissue").with(csrfCookie())
                        .cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(-1)))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test
    void reissueWithInvalidRefreshTokenStillFailsWithRefreshError() throws Exception {
        mockMvc.perform(post("/api/auth/reissue").with(csrfCookie())
                        .cookie(new Cookie("access_token", "invalid-access"), new Cookie("refresh_token", "invalid-refresh")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_002"));
    }

    @Test
    void reissueWithExpiredRefreshTokenStillFailsWithRefreshError() throws Exception {
        var refresh = tokens.generateToken(User.builder().id(42L).role(Role.USER).build(),
                "test-session", Duration.ofMinutes(-1), TokenType.REFRESH);
        mockMvc.perform(post("/api/auth/reissue").with(csrfCookie())
                        .cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(-1))),
                                new Cookie("refresh_token", refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"));
    }

    @Test
    void reissueStillRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/auth/reissue")
                        .cookie(new Cookie("access_token", accessToken(Duration.ofMinutes(-1)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reissueRejectsMismatchedCsrfCookieAndHeader() throws Exception {
        mockMvc.perform(post("/api/auth/reissue")
                        .cookie(new Cookie("XSRF-TOKEN", "cookie-token"))
                        .header("X-XSRF-TOKEN", "different-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비회원도 기회 목록을 조회할 수 있다")
    void opportunityListIsPublic() throws Exception {
        mockMvc.perform(get("/api/opportunities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("비회원 기회 상세 요청은 인증 검사 없이 경로 변수 검증에 도달한다")
    void opportunityDetailIsPublic() throws Exception {
        mockMvc.perform(get("/api/opportunities/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("인증이 필요한 API는 비회원 접근을 거부한다")
    void protectedApiRejectsAnonymousUser() throws Exception {
        mockMvc.perform(get("/api/security-test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_006"));
    }

    @Test
    @WithMockUser(authorities = "USER")
    @DisplayName("인증 사용자라도 CSRF 토큰 없이 상태 변경을 요청하면 거부한다")
    void stateChangingRequestWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/api/security-test/change"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "USER")
    @DisplayName("인증 사용자에게 CSRF 토큰이 있으면 상태 변경을 허용한다")
    void stateChangingRequestWithCsrfIsAllowed() throws Exception {
        mockMvc.perform(
                        post("/api/security-test/change")
                                .with(csrfCookie())
                )
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("내부 API는 설정대로 CSRF 검사를 제외한다")
    void internalApiDoesNotRequireCsrf() throws Exception {
        mockMvc.perform(
                        post("/api/internal/security-test")
                                .header(
                                        "X-INTERNAL-KEY",
                                        "test-internal-api-key"
                                )
                )
                .andExpect(status().isOk());
    }

    /* 실제 API와 겹치지 않는 경로의 보안 설정을 검증한다. */
    @RestController
    public static class TestEndpointController {

        @GetMapping("/api/security-test/identity")
        public Long identity(@AuthenticationPrincipal CustomUserDetails user) {
            return user.getUserId();
        }

        @GetMapping("/api/security-test/protected")
        public String protectedEndpoint() {
            return "protected";
        }

        @PostMapping("/api/security-test/change")
        public String change() {
            return "changed";
        }

        @PostMapping("/api/internal/security-test")
        public String internal() {
            return "internal";
        }
    }
}
