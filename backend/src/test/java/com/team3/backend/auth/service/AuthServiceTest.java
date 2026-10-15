package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("로그아웃하면 현재 로그인 세션만 삭제한다")
    void deletesOnlyCurrentSessionOnLogout() {
        authService.logout(
                1L,
                "session-1"
        );

        verify(refreshTokenRepository)
                .deleteByUserIdAndSessionId(
                        1L,
                        "session-1"
                );

        verify(refreshTokenRepository, never())
                .deleteAllByUserId(anyLong());
    }

    @Test
    @DisplayName("전체 로그아웃하면 사용자의 모든 세션을 삭제한다")
    void deletesAllSessionsOnLogoutAll() {
        authService.logoutAll(1L);

        verify(refreshTokenRepository)
                .deleteAllByUserId(1L);

        verify(refreshTokenRepository, never())
                .deleteByUserIdAndSessionId(
                        anyLong(),
                        org.mockito.ArgumentMatchers.anyString()
                );
    }
}