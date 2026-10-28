package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final OutboxEventRepository outboxRepository;
    private final Clock clock;

    /**
     * Ends one login session. The push Endpoints that this session registered are deactivated by
     * notification-service through the USER_SESSION_ENDED Outbox, in the same transaction as the Refresh Token
     * deletion, so a failed browser-side cleanup cannot leave the previous account's token active.
     */
    @Transactional
    public void logout(
            Long userId,
            String sessionId
    ) {
        refreshTokenRepository
                .deleteByUserIdAndSessionId(
                        userId,
                        sessionId
                );

        outboxRepository.saveAndFlush(
                OutboxEvent.userSessionEnded(userId, sessionId, clock.instant())
        );
    }

    @Transactional
    public void logoutAll(Long userId) {
        refreshTokenRepository.deleteAllByUserId(userId);
    }
}
