package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.entity.OutboxStatus;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private OutboxEventRepository outboxRepository;

    @Spy
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("로그아웃하면 현재 로그인 세션만 삭제하고 그 세션의 종료 메시지를 Outbox에 남긴다")
    void deletesOnlyCurrentSessionOnLogoutAndRecordsSessionEnd() {
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

        var saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("USER_SESSION_ENDED");
        assertThat(saved.getValue().getUserId()).isEqualTo(1L);
        assertThat(saved.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getValue().getNextRetryAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getPayload())
                .containsEntry("sessionId", "session-1")
                .containsEntry("userId", 1L)
                .containsEntry("occurredAt", NOW.toString());
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

        verifyNoInteractions(outboxRepository);
        verify(outboxRepository, never()).saveAndFlush(any());
    }
}
