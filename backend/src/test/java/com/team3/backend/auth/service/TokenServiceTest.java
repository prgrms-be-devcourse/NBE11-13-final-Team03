package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.JwtProperties;
import com.team3.backend.auth.jwt.RefreshTokenHasher;
import com.team3.backend.auth.jwt.TokenProvider;
import com.team3.backend.auth.jwt.TokenStatus;
import com.team3.backend.auth.jwt.TokenType;
import com.team3.backend.auth.session.AuthSessionProperties;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private UserRepository userRepository;

    private JwtProperties jwtProperties;
    private AuthSessionProperties sessionProperties;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenValidity(Duration.ofHours(2));
        jwtProperties.setRefreshTokenValidity(Duration.ofDays(7));

        sessionProperties = new AuthSessionProperties();
        sessionProperties.setMaxActiveSessions(3);

        tokenService = new TokenService(
                tokenProvider,
                jwtProperties,
                refreshTokenHasher,
                refreshTokenRepository,
                userRepository,
                sessionProperties
        );
    }

    @Test
    @DisplayName("새로운 기기에서 로그인하면 새로운 세션을 저장한다")
    void issuesTokenForNewDevice() {
        User user = createUser(1L);
        String deviceId = "device-1";

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        when(refreshTokenRepository
                .findAllByUserIdAndExpiresAtLessThanEqual(
                        eq(1L),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of());

        when(refreshTokenRepository
                .findByUserIdAndDeviceId(1L, deviceId))
                .thenReturn(Optional.empty());

        when(refreshTokenRepository.countByUserId(1L))
                .thenReturn(0L);

        when(tokenProvider.generateToken(
                eq(user),
                anyString(),
                eq(jwtProperties.getAccessTokenValidity()),
                eq(TokenType.ACCESS)
        )).thenReturn("access-token");

        when(tokenProvider.generateToken(
                eq(user),
                anyString(),
                eq(jwtProperties.getRefreshTokenValidity()),
                eq(TokenType.REFRESH)
        )).thenReturn("refresh-token");

        when(refreshTokenHasher.hash("refresh-token"))
                .thenReturn("refresh-token-hash");

        TokenService.TokenPair result =
                tokenService.issueToken(user, deviceId);

        assertEquals("access-token", result.accessToken());
        assertEquals("refresh-token", result.refreshToken());

        ArgumentCaptor<RefreshToken> captor =
                ArgumentCaptor.forClass(RefreshToken.class);

        verify(refreshTokenRepository)
                .saveAndFlush(captor.capture());

        RefreshToken savedSession = captor.getValue();

        assertEquals(user, savedSession.getUser());
        assertEquals(deviceId, savedSession.getDeviceId());
        assertEquals(
                "refresh-token-hash",
                savedSession.getTokenHash()
        );
        assertNotNull(savedSession.getSessionId());
        assertDoesNotThrow(() ->
                UUID.fromString(savedSession.getSessionId())
        );
        assertTrue(
                savedSession.getExpiresAt()
                        .isAfter(LocalDateTime.now())
        );

        /*
         * Access Token과 Refresh Token에 같은 sessionId가
         * 전달됐는지 확인한다.
         */
        verify(tokenProvider).generateToken(
                user,
                savedSession.getSessionId(),
                jwtProperties.getAccessTokenValidity(),
                TokenType.ACCESS
        );

        verify(tokenProvider).generateToken(
                user,
                savedSession.getSessionId(),
                jwtProperties.getRefreshTokenValidity(),
                TokenType.REFRESH
        );
    }

    @Test
    @DisplayName("같은 기기에서 다시 로그인하면 기존 세션의 토큰을 교체한다")
    void rotatesExistingSessionForSameDevice() {
        User user = createUser(1L);
        String deviceId = "device-1";
        String sessionId = "session-1";

        RefreshToken existingSession = createRefreshToken(
                10L,
                user,
                sessionId,
                deviceId,
                "old-token-hash",
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().minusHours(1)
        );

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        when(refreshTokenRepository
                .findAllByUserIdAndExpiresAtLessThanEqual(
                        eq(1L),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of());

        when(refreshTokenRepository
                .findByUserIdAndDeviceId(1L, deviceId))
                .thenReturn(Optional.of(existingSession));

        when(tokenProvider.generateToken(
                user,
                sessionId,
                jwtProperties.getAccessTokenValidity(),
                TokenType.ACCESS
        )).thenReturn("new-access-token");

        when(tokenProvider.generateToken(
                user,
                sessionId,
                jwtProperties.getRefreshTokenValidity(),
                TokenType.REFRESH
        )).thenReturn("new-refresh-token");

        when(refreshTokenHasher.hash("new-refresh-token"))
                .thenReturn("new-token-hash");

        TokenService.TokenPair result =
                tokenService.issueToken(user, deviceId);

        assertEquals("new-access-token", result.accessToken());
        assertEquals("new-refresh-token", result.refreshToken());
        assertEquals(
                "new-token-hash",
                existingSession.getTokenHash()
        );
        assertEquals(
                sessionId,
                existingSession.getSessionId()
        );

        verify(refreshTokenRepository)
                .saveAndFlush(existingSession);

        /*
         * 같은 기기의 기존 세션을 갱신하므로
         * 활성 세션 개수 확인과 LRU 제거는 수행하지 않는다.
         */
        verify(refreshTokenRepository, never())
                .countByUserId(anyLong());

        verify(refreshTokenRepository, never())
                .delete(any(RefreshToken.class));
    }

    @Test
    @DisplayName("활성 세션이 3개이면 가장 오래된 세션을 제거한다")
    void evictsOldestSessionWhenLimitReached() {
        User user = createUser(1L);
        String newDeviceId = "device-4";

        RefreshToken oldestSession = createRefreshToken(
                10L,
                user,
                "session-oldest",
                "device-1",
                "oldest-token-hash",
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().minusDays(3)
        );

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        when(refreshTokenRepository
                .findAllByUserIdAndExpiresAtLessThanEqual(
                        eq(1L),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of());

        when(refreshTokenRepository
                .findByUserIdAndDeviceId(1L, newDeviceId))
                .thenReturn(Optional.empty());

        when(refreshTokenRepository.countByUserId(1L))
                .thenReturn(3L);

        when(refreshTokenRepository
                .findFirstByUserIdOrderByLastUsedAtAscCreatedAtAsc(1L))
                .thenReturn(Optional.of(oldestSession));

        when(tokenProvider.generateToken(
                eq(user),
                anyString(),
                eq(jwtProperties.getAccessTokenValidity()),
                eq(TokenType.ACCESS)
        )).thenReturn("access-token");

        when(tokenProvider.generateToken(
                eq(user),
                anyString(),
                eq(jwtProperties.getRefreshTokenValidity()),
                eq(TokenType.REFRESH)
        )).thenReturn("refresh-token");

        when(refreshTokenHasher.hash("refresh-token"))
                .thenReturn("refresh-token-hash");

        tokenService.issueToken(user, newDeviceId);

        ArgumentCaptor<RefreshToken> newSessionCaptor =
                ArgumentCaptor.forClass(RefreshToken.class);

        InOrder inOrder = inOrder(refreshTokenRepository);

        inOrder.verify(refreshTokenRepository)
                .delete(oldestSession);

        inOrder.verify(refreshTokenRepository)
                .flush();

        inOrder.verify(refreshTokenRepository)
                .saveAndFlush(newSessionCaptor.capture());

        RefreshToken newSession = newSessionCaptor.getValue();

        assertEquals(newDeviceId, newSession.getDeviceId());
        assertNotEquals(
                oldestSession.getSessionId(),
                newSession.getSessionId()
        );
    }

    @Test
    @DisplayName("로그인 시 만료된 세션을 먼저 삭제한다")
    void removesExpiredSessionsBeforeIssuingToken() {
        User user = createUser(1L);

        RefreshToken expiredSession = createRefreshToken(
                10L,
                user,
                "expired-session",
                "old-device",
                "expired-token-hash",
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().minusDays(1)
        );

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        when(refreshTokenRepository
                .findAllByUserIdAndExpiresAtLessThanEqual(
                        eq(1L),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of(expiredSession));

        when(refreshTokenRepository
                .findByUserIdAndDeviceId(1L, "new-device"))
                .thenReturn(Optional.empty());

        when(refreshTokenRepository.countByUserId(1L))
                .thenReturn(0L);

        when(tokenProvider.generateToken(
                eq(user),
                anyString(),
                eq(jwtProperties.getAccessTokenValidity()),
                eq(TokenType.ACCESS)
        )).thenReturn("access-token");

        when(tokenProvider.generateToken(
                eq(user),
                anyString(),
                eq(jwtProperties.getRefreshTokenValidity()),
                eq(TokenType.REFRESH)
        )).thenReturn("refresh-token");

        when(refreshTokenHasher.hash("refresh-token"))
                .thenReturn("refresh-token-hash");

        tokenService.issueToken(user, "new-device");

        verify(refreshTokenRepository)
                .deleteAll(List.of(expiredSession));

        /*
         * 만료 세션 삭제 후 한 번,
         * 새 세션 saveAndFlush 내부에서 저장한다.
         */
        verify(refreshTokenRepository).flush();
        verify(refreshTokenRepository)
                .saveAndFlush(any(RefreshToken.class));
    }

    @Test
    @DisplayName("deviceId가 없으면 토큰을 발급하지 않는다")
    void rejectsBlankDeviceId() {
        User user = createUser(1L);

        assertThrows(
                IllegalArgumentException.class,
                () -> tokenService.issueToken(user, " ")
        );

        verifyNoInteractions(
                userRepository,
                refreshTokenRepository,
                tokenProvider,
                refreshTokenHasher
        );
    }

    @Test
    @DisplayName("재발급 시 세션을 잠금 조회하고 Refresh Token을 교체한다")
    void reissuesAndRotatesRefreshToken() {
        String oldRefreshToken = "old-refresh-token";
        String sessionId = "session-1";

        User user = createUser(1L);

        RefreshToken storedToken = createRefreshToken(
                10L,
                user,
                sessionId,
                "device-1",
                "old-token-hash",
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().minusHours(1)
        );

        when(tokenProvider.validateToken(
                oldRefreshToken,
                TokenType.REFRESH
        )).thenReturn(TokenStatus.VALID);

        when(tokenProvider.getUserId(oldRefreshToken))
                .thenReturn(1L);

        when(tokenProvider.getSessionId(oldRefreshToken))
                .thenReturn(sessionId);

        when(refreshTokenRepository
                .findBySessionIdForUpdate(sessionId))
                .thenReturn(Optional.of(storedToken));

        when(refreshTokenHasher.matches(
                "old-token-hash",
                oldRefreshToken
        )).thenReturn(true);

        when(tokenProvider.generateToken(
                user,
                sessionId,
                jwtProperties.getAccessTokenValidity(),
                TokenType.ACCESS
        )).thenReturn("new-access-token");

        when(tokenProvider.generateToken(
                user,
                sessionId,
                jwtProperties.getRefreshTokenValidity(),
                TokenType.REFRESH
        )).thenReturn("new-refresh-token");

        when(refreshTokenHasher.hash("new-refresh-token"))
                .thenReturn("new-token-hash");

        TokenService.TokenPair result =
                tokenService.reissueToken(oldRefreshToken);

        assertEquals("new-access-token", result.accessToken());
        assertEquals("new-refresh-token", result.refreshToken());
        assertEquals(
                "new-token-hash",
                storedToken.getTokenHash()
        );
        assertEquals(sessionId, storedToken.getSessionId());

        verify(refreshTokenRepository)
                .findBySessionIdForUpdate(sessionId);

        verify(refreshTokenRepository)
                .saveAndFlush(storedToken);
    }

    @Test
    @DisplayName("저장된 Refresh Token 해시와 다르면 재발급을 거부한다")
    void rejectsMismatchedRefreshToken() {
        String requestedToken = "stolen-refresh-token";
        String sessionId = "session-1";

        User user = createUser(1L);

        RefreshToken storedToken = createRefreshToken(
                10L,
                user,
                sessionId,
                "device-1",
                "stored-token-hash",
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().minusHours(1)
        );

        when(tokenProvider.validateToken(
                requestedToken,
                TokenType.REFRESH
        )).thenReturn(TokenStatus.VALID);

        when(tokenProvider.getUserId(requestedToken))
                .thenReturn(1L);

        when(tokenProvider.getSessionId(requestedToken))
                .thenReturn(sessionId);

        when(refreshTokenRepository
                .findBySessionIdForUpdate(sessionId))
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
    }

    @Test
    @DisplayName("JWT 사용자와 저장된 세션 사용자가 다르면 재발급을 거부한다")
    void rejectsSessionOwnedByAnotherUser() {
        String refreshToken = "refresh-token";
        String sessionId = "session-1";

        User sessionOwner = createUser(2L);

        RefreshToken storedToken = createRefreshToken(
                10L,
                sessionOwner,
                sessionId,
                "device-1",
                "stored-token-hash",
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().minusHours(1)
        );

        when(tokenProvider.validateToken(
                refreshToken,
                TokenType.REFRESH
        )).thenReturn(TokenStatus.VALID);

        /*
         * JWT에는 userId=1이 있지만,
         * 조회된 세션의 소유자는 userId=2이다.
         */
        when(tokenProvider.getUserId(refreshToken))
                .thenReturn(1L);

        when(tokenProvider.getSessionId(refreshToken))
                .thenReturn(sessionId);

        when(refreshTokenRepository
                .findBySessionIdForUpdate(sessionId))
                .thenReturn(Optional.of(storedToken));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenService.reissueToken(refreshToken)
        );

        assertSame(
                AuthErrorCode.REFRESH_TOKEN_MISMATCH,
                exception.getErrorCode()
        );

        verifyNoInteractions(refreshTokenHasher);

        verify(refreshTokenRepository, never())
                .saveAndFlush(any());
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

        verifyNoInteractions(
                refreshTokenRepository,
                refreshTokenHasher
        );
    }

    @Test
    @DisplayName("JWT Refresh Token이 만료됐으면 재발급을 거부한다")
    void rejectsExpiredJwtRefreshToken() {
        String refreshToken = "expired-jwt-refresh-token";

        when(tokenProvider.validateToken(
                refreshToken,
                TokenType.REFRESH
        )).thenReturn(TokenStatus.EXPIRED);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenService.reissueToken(refreshToken)
        );

        assertSame(
                AuthErrorCode.EXPIRED_REFRESH_TOKEN,
                exception.getErrorCode()
        );

        verifyNoInteractions(
                refreshTokenRepository,
                refreshTokenHasher
        );
    }

    @Test
    @DisplayName("유효하지 않은 JWT Refresh Token이면 재발급을 거부한다")
    void rejectsInvalidJwtRefreshToken() {
        String refreshToken = "invalid-refresh-token";

        when(tokenProvider.validateToken(
                refreshToken,
                TokenType.REFRESH
        )).thenReturn(TokenStatus.INVALID);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenService.reissueToken(refreshToken)
        );

        assertSame(
                AuthErrorCode.INVALID_REFRESH_TOKEN,
                exception.getErrorCode()
        );

        verifyNoInteractions(
                refreshTokenRepository,
                refreshTokenHasher
        );
    }

    @Test
    @DisplayName("DB에 저장된 Refresh Token이 만료되면 재발급을 거부한다")
    void rejectsExpiredStoredRefreshToken() {
        String refreshToken = "refresh-token";
        String sessionId = "session-1";

        User user = createUser(1L);

        RefreshToken storedToken = createRefreshToken(
                10L,
                user,
                sessionId,
                "device-1",
                "expired-token-hash",
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().minusDays(1)
        );

        when(tokenProvider.validateToken(
                refreshToken,
                TokenType.REFRESH
        )).thenReturn(TokenStatus.VALID);

        when(tokenProvider.getUserId(refreshToken))
                .thenReturn(1L);

        when(tokenProvider.getSessionId(refreshToken))
                .thenReturn(sessionId);

        when(refreshTokenRepository
                .findBySessionIdForUpdate(sessionId))
                .thenReturn(Optional.of(storedToken));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenService.reissueToken(refreshToken)
        );

        assertSame(
                AuthErrorCode.EXPIRED_REFRESH_TOKEN,
                exception.getErrorCode()
        );

        /*
         * 재발급 요청 중에는 만료 세션을 삭제하지 않는다.
         * 예외로 트랜잭션이 롤백될 때 발생하는 정합성 문제를 피한다.
         */
        verify(refreshTokenRepository, never())
                .delete(any(RefreshToken.class));

        verify(refreshTokenRepository, never())
                .saveAndFlush(any());

        verifyNoInteractions(refreshTokenHasher);
    }

    private User createUser(Long userId) {
        return User.builder()
                .id(userId)
                .kakaoId(12345L + userId)
                .email("user" + userId + "@example.com")
                .nickname("사용자" + userId)
                .role(Role.USER)
                .build();
    }

    private RefreshToken createRefreshToken(
            Long id,
            User user,
            String sessionId,
            String deviceId,
            String tokenHash,
            LocalDateTime expiresAt,
            LocalDateTime lastUsedAt
    ) {
        return RefreshToken.builder()
                .id(id)
                .user(user)
                .sessionId(sessionId)
                .deviceId(deviceId)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .lastUsedAt(lastUsedAt)
                .build();
    }
}