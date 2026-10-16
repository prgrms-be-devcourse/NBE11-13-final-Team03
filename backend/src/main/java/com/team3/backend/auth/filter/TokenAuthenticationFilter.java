package com.team3.backend.auth.filter;

import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.TokenProvider;
import com.team3.backend.auth.jwt.TokenStatus;
import com.team3.backend.auth.jwt.TokenType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenProvider tokenProvider;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = resolveToken(request);

        if (token == null) {
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

        if (status == TokenStatus.EXPIRED) {
            request.setAttribute(
                    "auth_error",
                    AuthErrorCode.INVALID_ACCESS_TOKEN
            );

            filterChain.doFilter(request, response);
            return;
        }

        if (status == TokenStatus.INVALID) {
            request.setAttribute(
                    "auth_error",
                    AuthErrorCode.INVALID_ACCESS_TOKEN
            );

            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication =
                tokenProvider.getAuthentication(token);

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {

        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if ("access_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }
}