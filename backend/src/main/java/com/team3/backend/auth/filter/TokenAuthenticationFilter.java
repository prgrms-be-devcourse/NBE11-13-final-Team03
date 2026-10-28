package com.team3.backend.auth.filter;

import com.team3.backend.auth.cookie.TokenCookieManager;
import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.TokenProvider;
import com.team3.backend.auth.jwt.TokenStatus;
import com.team3.backend.auth.jwt.TokenType;
import com.team3.backend.auth.security.CustomAuthenticationEntryPoint;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    /** 만료된 Access Token 쿠키가 있어도 통과해야 하는 경로. SecurityConfig의 permitAll 목록과 같은 값을 쓴다. */
    public static final String REISSUE_PATH = "/api/auth/reissue";
    /** 프론트가 재발급 전에 CSRF 쿠키를 받는 비개인화 공개 GET. SecurityConfig의 permitAll 목록과 같은 값을 쓴다. */
    public static final String INTEREST_OPTIONS_PATH = "/api/interests/options";
    public static final String INTERNAL_API_PREFIX = "/api/internal/";

    private final TokenProvider tokenProvider;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // OAuth/static pages and refresh must remain usable with an expired access cookie.
        // The frontend bootstraps CSRF on this non-personalized public GET before refreshing.
        return !path.startsWith("/api/")
                || path.startsWith(INTERNAL_API_PREFIX)
                || path.equals(REISSUE_PATH)
                || ("GET".equals(request.getMethod()) && path.equals(INTEREST_OPTIONS_PATH));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = readCookie(request, TokenCookieManager.ACCESS_TOKEN_COOKIE);

        if (token == null) {
            if (readCookie(request, TokenCookieManager.REFRESH_TOKEN_COOKIE) != null) {
                // A refresh cookie without an access cookie is a logged-in browser whose access cookie
                // expired. Public endpoints must not answer it anonymously; the frontend refreshes and retries.
                reject(request, response, AuthErrorCode.ACCESS_TOKEN_NOT_FOUND);
                return;
            }

            request.setAttribute(
                    "auth_error",
                    AuthErrorCode.ACCESS_TOKEN_NOT_FOUND
            );

            filterChain.doFilter(request, response);
            return;
        }

        TokenStatus status =
                tokenProvider.validateToken(
                        token,
                        TokenType.ACCESS
                );

        if (status != TokenStatus.VALID) {
            reject(request, response, AuthErrorCode.INVALID_ACCESS_TOKEN);
            return;
        }

        Authentication authentication;
        try {
            authentication = tokenProvider.getAuthentication(token);
        } catch (JwtException | IllegalArgumentException failure) {
            // Parsing again may cross the expiry boundary or reject malformed principal claims.
            reject(request, response, AuthErrorCode.INVALID_ACCESS_TOKEN);
            return;
        }

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, AuthErrorCode errorCode) throws IOException {
        SecurityContextHolder.clearContext();
        request.setAttribute("auth_error", errorCode);
        authenticationEntryPoint.commence(request, response, new BadCredentialsException(errorCode.getMessage()));
    }

    private String readCookie(HttpServletRequest request, String name) {

        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }
}
