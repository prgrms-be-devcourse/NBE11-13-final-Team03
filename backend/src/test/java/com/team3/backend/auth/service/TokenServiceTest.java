package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.*;
import com.team3.backend.auth.redis.RefreshTokenCacheRepository;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private RefreshTokenHasher refreshTokenHasher;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private RefreshTokenCacheRepository refreshTokenCacheRepository;

    private TokenService tokenService;

    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenValidity(Duration.ofHours(2));
        jwtProperties.setRefreshTokenValidity(Duration.ofDays(7));

        tokenService = new TokenService(
                tokenProvider,
                jwtProperties,
                refreshTokenHasher,
                refreshTokenRepository,
                refreshTokenCacheRepository
        );
    }

    @Test
    @DisplayName("재발급 시 잠금 조회 후 Refresh Token을 교체한다")
    void reissuesAndRotatesRefreshToken() {
        String oldRefreshToken = "old-refresh-token";

        User user = User.builder()
                .id(1L)
                .kakaoId(12345L)
                .email("user@example.com")
                .nickname("사용자")
                .role(Role.USER)
                .build();

        RefreshToken storedToken = RefreshToken.builder()
                .id(10L)
                .user(user)
                .tokenHash("old-token-hash")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();

        when(tokenProvider.validateToken(
                oldRefreshToken,
                TokenType.REFRESH
        )).thenReturn(TokenStatus.VALID);

        when(tokenProvider.getUserId(oldRefreshToken))
                .thenReturn(1L);

        when(refreshTokenRepository.findByUserIdForUpdate(1L))
                .thenReturn(Optional.of(storedToken));

        when(refreshTokenHasher.matches(
                "old-token-hash",
                oldRefreshToken
        )).thenReturn(true);

        when(tokenProvider.generateToken(
                user,
                jwtProperties.getAccessTokenValidity(),
                TokenType.ACCESS
        )).thenReturn("new-access-token");

        when(tokenProvider.generateToken(
                user,
                jwtProperties.getRefreshTokenValidity(),
                TokenType.REFRESH
        )).thenReturn("new-refresh-token");

        when(refreshTokenHasher.hash("new-refresh-token"))
                .thenReturn("new-token-hash");

        TokenService.TokenPair result =
                tokenService.reissueToken(oldRefreshToken);

        assertEquals("new-access-token", result.accessToken());
        assertEquals("new-refresh-token", result.refreshToken());
        assertEquals("new-token-hash", storedToken.getTokenHash());

        verify(refreshTokenRepository)
                .findByUserIdForUpdate(1L);

        verify(refreshTokenRepository)
                .saveAndFlush(storedToken);

        verify(refreshTokenCacheRepository).save(
                eq(1L),
                eq("new-token-hash"),
                any(Duration.class)
        );
    }

    @Test
    @DisplayName("저장된 Refresh Token과 다르면 재발급하지 않는다")
    void rejectsMismatchedRefreshToken() {
        String requestedToken = "stolen-refresh-token";

        User user = User.builder()
                .id(1L)
                .role(Role.USER)
                .build();

        RefreshToken storedToken = RefreshToken.builder()
                .id(10L)
                .user(user)
                .tokenHash("stored-token-hash")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();

        when(tokenProvider.validateToken(
                requestedToken,
                TokenType.REFRESH
        )).thenReturn(TokenStatus.VALID);

        when(tokenProvider.getUserId(requestedToken))
                .thenReturn(1L);

        when(refreshTokenRepository.findByUserIdForUpdate(1L))
                .thenReturn(Optional.of(storedToken));

        when(refreshTokenHasher.matches(
                "stored-token-hash",
                requestedToken
        )).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenService.reissueToken(requestedToken)
        );

        assertSame(
                AuthErrorCode.REFRESH_TOKEN_MISMATCH,
                exception.getErrorCode()
        );

        verify(refreshTokenRepository, never())
                .saveAndFlush(any());

        verify(refreshTokenCacheRepository, never())
                .save(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("Refresh Token이 없으면 재발급을 거부한다")
    void rejectsMissingRefreshToken() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenService.reissueToken(null)
        );

        assertSame(
                AuthErrorCode.REFRESH_TOKEN_NOT_FOUND,
                exception.getErrorCode()
        );

        verifyNoInteractions(refreshTokenRepository);
    }
}