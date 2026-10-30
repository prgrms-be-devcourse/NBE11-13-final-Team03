package com.team3.backend.behavior.retention;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 매월 1일 03:00 KST에 행동 로그 보관 정리를 실행한다. 판정·보호 장치는 수동 실행과 같은
 * {@link UserBehaviorEventRetention#runNow}를 쓴다.
 *
 * 설정 (application.yaml에 없으면 기본값)
 * - yon.behavior.retention.enabled: true일 때만 등록한다 (기본 false). app.scheduling.enabled=false여도 꺼진다.
 *   운영 DB의 파티션 전환과 점검을 확인한 환경에서만 켠다.
 * - yon.behavior.retention.retained-months: 현재 월을 포함해 보관하는 월 수 (기본 4: 현재 월 + 직전 3개월, 1 이상)
 * - yon.behavior.retention.dry-run: true면 삭제·생성 대상만 로그로 남긴다 (기본 true). 실제 삭제는 false로 명시해야 한다.
 * - yon.behavior.retention.lock-timeout: 파티션 DDL 하나가 부모 테이블 잠금을 기다리는 최대 시간 (기본 3s)
 */
@Slf4j
@Component
@ConditionalOnExpression("${app.scheduling.enabled:true} && ${yon.behavior.retention.enabled:false}")
public class UserBehaviorEventRetentionScheduler {

    private final UserBehaviorEventRetention retention;
    private final Clock clock;
    private final int retainedMonths;
    private final boolean dryRun;
    private final Duration lockTimeout;

    public UserBehaviorEventRetentionScheduler(UserBehaviorEventRetention retention, Clock clock,
                                               @Value("${yon.behavior.retention.retained-months:4}") int retainedMonths,
                                               @Value("${yon.behavior.retention.dry-run:true}") boolean dryRun,
                                               @Value("${yon.behavior.retention.lock-timeout:3s}") Duration lockTimeout) {
        if (retainedMonths < 1) {
            throw new IllegalArgumentException("yon.behavior.retention.retained-months must be at least 1: " + retainedMonths);
        }
        this.retention = retention;
        this.clock = clock;
        this.retainedMonths = retainedMonths;
        this.dryRun = dryRun;
        this.lockTimeout = lockTimeout;
        log.info("Behavior event retention scheduled: monthly 03:00 KST on day 1, retainedMonths={}, dryRun={}, lockTimeout={}",
                retainedMonths, dryRun, lockTimeout);
    }

    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Seoul")
    public void run() {
        try {
            runOnce();
        } catch (RuntimeException exception) {
            log.error("Behavior event retention run failed", exception);
        }
    }

    UserBehaviorEventRetention.Result runOnce() {
        return retention.runNow(clock, "scheduled", retainedMonths, dryRun, lockTimeout);
    }
}
