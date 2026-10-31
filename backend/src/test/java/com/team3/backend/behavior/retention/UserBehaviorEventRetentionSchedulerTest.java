package com.team3.backend.behavior.retention;

import com.team3.backend.behavior.retention.UserBehaviorEventRetention.Result;
import com.team3.backend.behavior.retention.UserBehaviorEventRetention.Status;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

/** 자동 정리는 명시적으로 켠 환경에서만, 수동 정리는 스케줄러와 무관하게 수동 모드에서만 등록되고 기본은 4개월·dry-run이다. */
class UserBehaviorEventRetentionSchedulerTest {

    // 앱처럼 "3s" 같은 Duration 값을 변환한다.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(UserBehaviorEventRetention.class, () -> mock(UserBehaviorEventRetention.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(UserBehaviorEventRetentionScheduler.class, UserBehaviorEventRetentionManualRun.class);

    @Test
    void notRegisteredUnlessExplicitlyEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(UserBehaviorEventRetentionScheduler.class);
            assertThat(context).doesNotHaveBean(UserBehaviorEventRetentionManualRun.class);
        });
        runner.withPropertyValues("yon.behavior.retention.enabled=true", "app.scheduling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UserBehaviorEventRetentionScheduler.class));
    }

    @Test
    void enabledSchedulerDefaultsToFourMonthsAndDryRun() {
        runner.withPropertyValues("yon.behavior.retention.enabled=true").run(context -> {
            UserBehaviorEventRetentionScheduler scheduler = context.getBean(UserBehaviorEventRetentionScheduler.class);
            assertThat(ReflectionTestUtils.getField(scheduler, "retainedMonths")).isEqualTo(4);
            assertThat(ReflectionTestUtils.getField(scheduler, "dryRun")).isEqualTo(true);
        });
    }

    @Test
    void retainedMonthsIsConfigurableAndMustBePositive() {
        runner.withPropertyValues("yon.behavior.retention.enabled=true", "yon.behavior.retention.retained-months=6",
                "yon.behavior.retention.dry-run=false").run(context -> {
            UserBehaviorEventRetentionScheduler scheduler = context.getBean(UserBehaviorEventRetentionScheduler.class);
            assertThat(ReflectionTestUtils.getField(scheduler, "retainedMonths")).isEqualTo(6);
            assertThat(ReflectionTestUtils.getField(scheduler, "dryRun")).isEqualTo(false);
        });
        runner.withPropertyValues("yon.behavior.retention.enabled=true", "yon.behavior.retention.retained-months=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("yon.behavior.retention.manual-run=true", "yon.behavior.retention.retained-months=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void manualRunIsIndependentOfSchedulerFlagsAndDefaultsToDryRunWithSameMonths() {
        runner.withPropertyValues("yon.behavior.retention.manual-run=true", "yon.behavior.retention.enabled=false",
                "app.scheduling.enabled=false", "yon.behavior.retention.retained-months=5").run(context -> {
            assertThat(context).doesNotHaveBean(UserBehaviorEventRetentionScheduler.class);
            UserBehaviorEventRetentionManualRun manual = context.getBean(UserBehaviorEventRetentionManualRun.class);
            assertThat(ReflectionTestUtils.getField(manual, "execute")).isEqualTo(false);
            assertThat(ReflectionTestUtils.getField(manual, "retainedMonths")).isEqualTo(5);
        });
        runner.withPropertyValues("yon.behavior.retention.manual-run=true", "yon.behavior.retention.manual-execute=true")
                .run(context -> assertThat(ReflectionTestUtils.getField(
                        context.getBean(UserBehaviorEventRetentionManualRun.class), "execute")).isEqualTo(true));
    }

    @Test
    void manualRunRefusesInsideServingWebBackend() {
        UserBehaviorEventRetention retention = mock(UserBehaviorEventRetention.class);
        ConfigurableApplicationContext web = mock(ConfigurableApplicationContext.class,
                withSettings().extraInterfaces(WebServerApplicationContext.class));
        new UserBehaviorEventRetentionManualRun(retention, web, Clock.systemUTC(), 4, true, Duration.ofSeconds(3)).run(null);
        verifyNoInteractions(retention);
    }

    @Test
    void exitCodesDistinguishCompletedFailedAndSkipped() {
        assertThat(UserBehaviorEventRetentionManualRun.exitCode(result(Status.COMPLETED))).isZero();
        assertThat(UserBehaviorEventRetentionManualRun.exitCode(result(Status.COMPLETED_WITH_FAILURES))).isEqualTo(1);
        for (Status skipped : List.of(Status.SKIPPED_LOCKED, Status.SKIPPED_NOT_PARTITIONED,
                Status.SKIPPED_ROLLBACK_WINDOW, Status.SKIPPED_CLOCK_MISMATCH)) {
            assertThat(UserBehaviorEventRetentionManualRun.exitCode(result(skipped))).isEqualTo(2);
        }
    }

    private static Result result(Status status) {
        return new Result(status, null, true, List.of(), List.of(), List.of(), List.of());
    }
}
