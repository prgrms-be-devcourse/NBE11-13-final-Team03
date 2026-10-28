package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.entity.RefreshToken;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

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
    @DisplayName("전체 로그아웃하면 사용자의 모든 세션을 삭제하고 세션마다 종료 메시지를 남긴다")
    void deletesAllSessionsOnLogoutAllAndRecordsEverySessionEnd() {
        when(refreshTokenRepository.findAllByUserId(1L)).thenReturn(List.of(
                RefreshToken.builder().sessionId("session-1").build(),
                RefreshToken.builder().sessionId("session-2").build()
        ));

        authService.logoutAll(1L, "session-1");

        verify(refreshTokenRepository)
                .deleteAllByUserId(1L);

        verify(refreshTokenRepository, never())
                .deleteByUserIdAndSessionId(
                        anyLong(),
                        org.mockito.ArgumentMatchers.anyString()
                );

        var saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo("USER_SESSION_ENDED");
                    assertThat(event.getUserId()).isEqualTo(1L);
                })
                .extracting(event -> event.getPayload().get("sessionId"))
                .containsExactly("session-1", "session-2");
    }

    @Test
    @DisplayName("전체 로그아웃은 저장된 Refresh Token이 없어도 현재 세션의 종료 메시지를 남긴다")
    void logoutAllAlwaysEndsTheCallingSession() {
        when(refreshTokenRepository.findAllByUserId(1L)).thenReturn(List.of());

        authService.logoutAll(1L, "session-1");

        var saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPayload()).containsEntry("sessionId", "session-1");
    }
}
