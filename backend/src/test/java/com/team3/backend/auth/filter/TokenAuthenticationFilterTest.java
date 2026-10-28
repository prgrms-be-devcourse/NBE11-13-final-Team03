package com.team3.backend.auth.filter;

import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.TokenProvider;
import com.team3.backend.auth.jwt.TokenStatus;
import com.team3.backend.auth.jwt.TokenType;
import com.team3.backend.auth.security.CustomAuthenticationEntryPoint;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenAuthenticationFilterTest {
    @Mock private TokenProvider tokens;
    @Mock private CustomAuthenticationEntryPoint entryPoint;
    @Mock private FilterChain chain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void parsingFailureAfterValidationReturnsUnauthorizedInsteadOfServerError() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/opportunities");
        var response = new MockHttpServletResponse();
        request.setCookies(new Cookie("access_token", "access"));
        when(tokens.validateToken("access", TokenType.ACCESS)).thenReturn(TokenStatus.VALID);
        when(tokens.getAuthentication("access")).thenThrow(new JwtException("expired during second parse"));

        new TokenAuthenticationFilter(tokens, entryPoint).doFilter(request, response, chain);

        verify(entryPoint).commence(eq(request), eq(response), any());
        verifyNoInteractions(chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectedTokenClearsAnyExistingAuthenticationAndStopsChain() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/opportunities");
        var response = new MockHttpServletResponse();
        request.setCookies(new Cookie("access_token", "expired"));
        when(tokens.validateToken("expired", TokenType.ACCESS)).thenReturn(TokenStatus.EXPIRED);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("old-principal", "", List.of()));

        new TokenAuthenticationFilter(tokens, entryPoint).doFilter(request, response, chain);

        verify(entryPoint).commence(eq(request), eq(response), any());
        verifyNoInteractions(chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void refreshCookieWithoutAccessCookieIsNotAnonymousOnPublicApi() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/opportunities");
        var response = new MockHttpServletResponse();
        request.setCookies(new Cookie("refresh_token", "refresh"));

        new TokenAuthenticationFilter(tokens, entryPoint).doFilter(request, response, chain);

        verify(entryPoint).commence(eq(request), eq(response), any());
        verifyNoInteractions(chain, tokens);
        assertThat(request.getAttribute("auth_error")).isEqualTo(AuthErrorCode.ACCESS_TOKEN_NOT_FOUND);
    }

    @Test
    void noCookiesAtAllRemainsAnonymous() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/opportunities");
        var response = new MockHttpServletResponse();

        new TokenAuthenticationFilter(tokens, entryPoint).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(tokens, entryPoint);
        assertThat(request.getAttribute("auth_error")).isEqualTo(AuthErrorCode.ACCESS_TOKEN_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/reissue", "/api/interests/options", "/api/internal/job", "/oauth2/authorization/kakao", "/login-success.html", "/js/app.js"})
    void recoveryAndNonApiPathsRemainReachableUnderContextPath(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", "/backend" + path);
        request.setContextPath("/backend");
        var response = new MockHttpServletResponse();
        request.setCookies(new Cookie("access_token", "expired"));

        new TokenAuthenticationFilter(tokens, entryPoint).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(tokens, entryPoint);
    }

    @Test
    void contextPathDoesNotBypassApiTokenValidation() throws Exception {
        var request = new MockHttpServletRequest("GET", "/backend/api/opportunities");
        request.setContextPath("/backend");
        var response = new MockHttpServletResponse();
        request.setCookies(new Cookie("access_token", "expired"));
        when(tokens.validateToken("expired", TokenType.ACCESS)).thenReturn(TokenStatus.EXPIRED);

        new TokenAuthenticationFilter(tokens, entryPoint).doFilter(request, response, chain);

        verify(entryPoint).commence(eq(request), eq(response), any());
        verifyNoInteractions(chain);
    }
}
