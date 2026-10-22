package com.team3.backend.eligibility.service;

import com.team3.backend.global.role.ApiRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 프로필 변경이 커밋된 뒤 그 사용자의 판정 결과를 비동기로 최신화한다.
 * 실패해도 프로필 저장에는 영향이 없고, 다음 조회 시 refresh/refreshAll이 다시 채운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ApiRole
public class EligibilityResultRefresher {

    private final EligibilityResultService resultService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileChanged(EligibilityProfileChangedEvent event) {
        try {
            int judged = resultService.refreshAll(event.userId());
            log.info("Eligibility results refreshed: userId={}, judged={}", event.userId(), judged);
        } catch (RuntimeException e) {
            log.warn("Eligibility result refresh failed: userId={}", event.userId(), e);
        }
    }
}
