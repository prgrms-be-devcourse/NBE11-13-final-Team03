package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.EligibilityFanoutSchedulingProperties;
import com.team3.backend.tracking.config.EventFanoutConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EligibilityFanoutSchedulingTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EventFanoutConfig.class, EligibilityFanoutWorker.class, EligibilityFanoutScheduler.class)
            .withBean(EligibilityFanoutCoordinator.class, () -> mock(EligibilityFanoutCoordinator.class))
            .withBean(EligibilityFanoutBatch.class, () -> mock(EligibilityFanoutBatch.class));

    @Test
    void defaultsDoNotRegisterWorkerOrScheduler() {
        context.run(result -> {
            assertThat(result).hasNotFailed().doesNotHaveBean(EligibilityFanoutWorker.class).doesNotHaveBean(EligibilityFanoutScheduler.class);
            var properties = result.getBean(EligibilityFanoutSchedulingProperties.class);
            assertThat(properties.enabled()).isFalse(); assertThat(properties.maxPagesPerRun()).isEqualTo(10);
            assertThat(properties.maxPagesPerEvent()).isEqualTo(2); assertThat(properties.pollIntervalMs()).isEqualTo(60000);
        });
    }

    @Test
    void schedulerFlagCannotBypassEitherWorkerGate() {
        for (String missing : new String[]{"yon.tracking.eligibility-fanout.enabled", "yon.tracking.eligibility-outbox.enabled"}) {
            context.withPropertyValues("yon.tracking.eligibility-fanout.scheduler.enabled=true",
                    "yon.tracking.eligibility-fanout.enabled=" + !missing.equals("yon.tracking.eligibility-fanout.enabled"),
                    "yon.tracking.eligibility-outbox.enabled=" + !missing.equals("yon.tracking.eligibility-outbox.enabled"))
                    .run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(EligibilityFanoutWorker.class).doesNotHaveBean(EligibilityFanoutScheduler.class));
        }
    }

    @Test
    void workerOptInAloneRemainsManual() {
        context.withPropertyValues("yon.tracking.eligibility-fanout.enabled=true", "yon.tracking.eligibility-outbox.enabled=true")
                .run(result -> assertThat(result).hasNotFailed().hasSingleBean(EligibilityFanoutWorker.class).doesNotHaveBean(EligibilityFanoutScheduler.class));
    }

    @Test
    void globalSchedulingOffKeepsManualWorker() {
        enabled().withPropertyValues("app.scheduling.enabled=false").run(result ->
                assertThat(result).hasNotFailed().hasSingleBean(EligibilityFanoutWorker.class).doesNotHaveBean(EligibilityFanoutScheduler.class));
    }

    @Test
    void allGatesRegisterSchedulerWithoutRequiringVersionPipeline() {
        enabled().run(result -> assertThat(result).hasNotFailed().hasSingleBean(EligibilityFanoutScheduler.class).hasSingleBean(EligibilityFanoutWorker.class));
    }

    @Test
    void invalidBudgetsAndPollIntervalFailBinding() {
        for (String property : new String[]{"max-pages-per-run=0", "max-pages-per-run=501", "max-pages-per-event=0", "max-pages-per-event=11", "poll-interval-ms=999"}) {
            context.withPropertyValues("yon.tracking.eligibility-fanout.scheduler." + property)
                    .run(result -> assertThat(result).hasFailed());
        }
    }

    @Test
    void pollFailureDoesNotPreventFollowingInvocation() {
        var worker = mock(EligibilityFanoutWorker.class); var scheduler = new EligibilityFanoutScheduler(worker);
        when(worker.runOnce()).thenThrow(new IllegalStateException("private failure"))
                .thenReturn(new EligibilityFanoutWorker.Run(false, 0, 0, 0, 0));
        assertThatNoException().isThrownBy(scheduler::run); assertThatNoException().isThrownBy(scheduler::run);
        verify(worker, times(2)).runOnce();
    }

    private ApplicationContextRunner enabled() {
        return context.withPropertyValues("yon.tracking.eligibility-fanout.enabled=true", "yon.tracking.eligibility-outbox.enabled=true",
                "yon.tracking.eligibility-fanout.scheduler.enabled=true");
    }
}
