package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;

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

    /**
     * Ends every login session of the user (all devices). One USER_SESSION_ENDED per stored session, plus the
     * calling session in case its Refresh Token row is already gone (expired or evicted) while its access token is valid.
     */
    @Transactional
    public void logoutAll(Long userId, String currentSessionId) {
        var sessionIds = new LinkedHashSet<String>();
        sessionIds.add(currentSessionId);
        refreshTokenRepository.findAllByUserId(userId).stream()
                .map(RefreshToken::getSessionId)
                .forEach(sessionIds::add);

        refreshTokenRepository.deleteAllByUserId(userId);

        Instant now = clock.instant();
        for (String sessionId : sessionIds) {
            outboxRepository.saveAndFlush(OutboxEvent.userSessionEnded(userId, sessionId, now));
        }
    }
}
