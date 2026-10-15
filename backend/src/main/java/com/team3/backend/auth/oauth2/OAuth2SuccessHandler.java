package com.team3.backend.auth.oauth2;

import com.team3.backend.auth.cookie.DeviceCookieManager;
import com.team3.backend.auth.cookie.TokenCookieManager;
import com.team3.backend.auth.service.TokenService;
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
    private final TokenCookieManager tokenCookieManager;
    private final DeviceCookieManager deviceCookieManager;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        log.info("OAuth2 로그인 성공 핸들러 실행");

        CustomOAuth2User principal =
                (CustomOAuth2User) authentication.getPrincipal();

        String deviceId =
                deviceCookieManager.resolveOrCreate(
                        request,
                        response
                );

        TokenService.TokenPair tokens =
                tokenService.issueToken(
                        principal.getUser(),
                        deviceId
                );

        tokenCookieManager.addTokenCookies(
                response,
                tokens
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
}
