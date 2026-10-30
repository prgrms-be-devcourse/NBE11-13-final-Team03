package com.team3.backend.behavior.retention;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 운영자 수동 정리. yon.behavior.retention.manual-run=true로 띄운 일회성 백엔드(웹 서버 없음)에서 한 번 실행하고 종료 코드로 끝난다.
 * 스케줄러 활성화(yon.behavior.retention.enabled)와 무관하지만 판정·보호 장치·advisory lock은 자동 실행과 같은
 * {@link UserBehaviorEventRetention#runNow}를, 보관 개월 수·잠금 시간은 같은 설정을 쓴다.
 *
 * - yon.behavior.retention.manual-execute: true일 때만 실제로 생성·삭제한다 (기본 false = dry-run)
 * - 종료 코드: 0 완료, 1 일부 실패, 2 건너뜀(잠금·일반 테이블·_old·시계 불일치), 3 실행 오류
 *
 * 웹 서버로 떠 있는 일반 백엔드에서는 실행하지 않는다. 설정이 남아 있어도 서비스가 정리 후 종료되지 않게 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yon.behavior.retention.manual-run", havingValue = "true")
public class UserBehaviorEventRetentionManualRun implements ApplicationRunner {

    private final UserBehaviorEventRetention retention;
    private final ConfigurableApplicationContext context;
    private final Clock clock;
    private final int retainedMonths;
    private final boolean execute;
    private final Duration lockTimeout;

    public UserBehaviorEventRetentionManualRun(UserBehaviorEventRetention retention, ConfigurableApplicationContext context,
                                               Clock clock,
                                               @Value("${yon.behavior.retention.retained-months:4}") int retainedMonths,
                                               @Value("${yon.behavior.retention.manual-execute:false}") boolean execute,
                                               @Value("${yon.behavior.retention.lock-timeout:3s}") Duration lockTimeout) {
        if (retainedMonths < 1) {
            throw new IllegalArgumentException("yon.behavior.retention.retained-months must be at least 1: " + retainedMonths);
        }
        this.retention = retention;
        this.context = context;
        this.clock = clock;
        this.retainedMonths = retainedMonths;
        this.execute = execute;
        this.lockTimeout = lockTimeout;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (context instanceof WebServerApplicationContext) {
            log.error("Behavior event manual retention refused: run it as a one-shot process with "
                    + "spring.main.web-application-type=none, not in the serving backend");
            return;
        }
        int code;
        try {
            code = exitCode(runOnce());
        } catch (RuntimeException exception) {
            log.error("Behavior event manual retention failed", exception);
            code = 3;
        }
        int exitCode = code;
        log.info("Behavior event manual retention exiting with code {}", exitCode);
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    UserBehaviorEventRetention.Result runOnce() {
        return retention.runNow(clock, execute ? "manual" : "manual-dry-run", retainedMonths, !execute, lockTimeout);
    }

    static int exitCode(UserBehaviorEventRetention.Result result) {
        return switch (result.status()) {
            case COMPLETED -> 0;
            case COMPLETED_WITH_FAILURES -> 1;
            case SKIPPED_LOCKED, SKIPPED_NOT_PARTITIONED, SKIPPED_ROLLBACK_WINDOW, SKIPPED_CLOCK_MISMATCH, PLAN_CHANGED -> 2;
        };
    }
}
