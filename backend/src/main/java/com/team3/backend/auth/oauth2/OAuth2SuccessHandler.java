package com.team3.backend.auth.oauth2;

import com.team3.backend.auth.jwt.JwtProperties;
import com.team3.backend.auth.service.TokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final TokenService tokenService;
    private final JwtProperties jwtProperties;
    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        log.info("OAuth2 로그인 성공 핸들러 실행");

        CustomOAuth2User principal =
                (CustomOAuth2User) authentication.getPrincipal();

        TokenService.TokenPair tokens =
                tokenService.issueToken(principal.getUser());

        addCookie(
                response,
                ACCESS_TOKEN_COOKIE,
                tokens.accessToken(),
                (int) jwtProperties
                        .getAccessTokenValidity()
                        .toSeconds()
        );

        addCookie(
                response,
                REFRESH_TOKEN_COOKIE,
                tokens.refreshToken(),
                (int) jwtProperties
                        .getRefreshTokenValidity()
                        .toSeconds()
        );
        log.info("JWT 쿠키 저장 완료");

        if (response.isCommitted()) {
            log.warn("Response already committed");
            return;
        }

        getRedirectStrategy().sendRedirect(
                request,
                response,
                "/login-success.html"
        );
    }

    private void addCookie(
            HttpServletResponse response,
            String name,
            String value,
            int maxAge
    ) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);

        response.addCookie(cookie);
    }
}
