package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.global.role.ApiRole;
import com.team3.backend.opportunity.conditionset.ConditionSetBackfill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 신규·변경 공고의 신청 가능성 판정 결과를 주기적으로 최신화한다.
 *
 * 판정 프로필이 있는 사용자마다 EligibilityResultService.refreshStale을 호출해, 결과가 없거나 오래된 조건 묶음만 판정한다.
 * 시작할 때 조건 묶음이 빈 기회 버전을 먼저 채운다 (ConditionSetBackfill).
 * 정규화로 공고가 새로 생기거나 새 버전이 생긴 경우, 생일이 지나 만 나이가 바뀐 경우, 판정 규칙 버전이 바뀐 경우가 모두 잡힌다.
 * 사용자 한 명 = 트랜잭션 하나이며, 한 사용자가 실패해도 다음 사용자를 계속 처리한다 (다음 실행 때 다시 시도).
 *
 * 설정 (application.yaml에 없으면 기본값)
 * - yon.eligibility.batch.enabled: false면 끈다 (기본 true). app.scheduling.enabled=false여도 꺼진다
 * - yon.eligibility.batch.interval-ms: 이전 실행이 끝난 뒤 다음 실행까지 (기본 10분)
 * - yon.eligibility.batch.initial-delay-ms: 앱 시작 후 첫 실행까지 (기본 5분)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ApiRole
@ConditionalOnExpression("${app.scheduling.enabled:true} && ${yon.eligibility.batch.enabled:true}")
public class EligibilityBatchRefresher {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int USER_PAGE = 500;

    private final EligibilityProfileRepository profileRepository;
    private final EligibilityResultService resultService;
    /** 조건 묶음이 빈 버전을 먼저 채운다 (정규화를 거치지 않고 만든 버전 등). 백필을 끈 환경에서는 없음 */
    private final ObjectProvider<ConditionSetBackfill> conditionSetBackfill;

    @Scheduled(fixedDelayString = "${yon.eligibility.batch.interval-ms:600000}",
            initialDelayString = "${yon.eligibility.batch.initial-delay-ms:300000}")
    public void run() {
        refreshAllUsers(OffsetDateTime.now(KST));
    }

    /** @return 새로 판정한 건수 합계 */
    public int refreshAllUsers(OffsetDateTime now) {
        long started = System.nanoTime();
        try {
            conditionSetBackfill.ifAvailable(ConditionSetBackfill::run);
        } catch (RuntimeException e) {
            log.warn("Condition set backfill before eligibility batch failed; continuing", e);
        }
        int users = 0;
        int failed = 0;
        int judged = 0;
        long afterUserId = 0L;
        List<Long> userIds;
        do {
            userIds = profileRepository.findUserIdsAfter(afterUserId, PageRequest.of(0, USER_PAGE));
            for (Long userId : userIds) {
                try {
                    judged += resultService.refreshStale(userId, now);
                    users++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Eligibility batch refresh failed: userId={}", userId, e);
                }
            }
            if (!userIds.isEmpty()) {
                afterUserId = userIds.getLast();
            }
        } while (userIds.size() == USER_PAGE);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (judged > 0 || failed > 0) {
            log.info("Eligibility batch refreshed: users={}, failed={}, judged={}, elapsedMs={}",
                    users, failed, judged, elapsedMs);
        } else {
            log.debug("Eligibility batch: nothing to refresh (users={}, elapsedMs={})", users, elapsedMs);
        }
        return judged;
    }
}
