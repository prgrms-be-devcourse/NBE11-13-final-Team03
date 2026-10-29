package com.team3.backend.tracking.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchAutomaticSchedulingTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(MatchAutomaticScheduler.class)
            .withBean(MatchAutomaticWorker.class,()->mock(MatchAutomaticWorker.class));

    @Test
    void defaultsAndPartialOptInDoNotRegisterScheduler() {
        context.run(result->assertThat(result).hasNotFailed().doesNotHaveBean(MatchAutomaticScheduler.class));
        context.withPropertyValues("yon.tracking.match.enabled=true","yon.tracking.match.outbox.enabled=true",
                "yon.tracking.match.automatic.enabled=true")
                .run(result->assertThat(result).hasNotFailed().doesNotHaveBean(MatchAutomaticScheduler.class));
    }

    @Test
    void allGatesAreRequiredAndGlobalSwitchCanStopAutomaticPolling() {
        var enabled=context.withPropertyValues("yon.tracking.match.enabled=true","yon.tracking.match.outbox.enabled=true",
                "yon.tracking.match.automatic.enabled=true","yon.tracking.match.automatic.scheduler-enabled=true");
        enabled.run(result->assertThat(result).hasNotFailed().hasSingleBean(MatchAutomaticScheduler.class));
        enabled.withPropertyValues("app.scheduling.enabled=false")
                .run(result->assertThat(result).hasNotFailed().doesNotHaveBean(MatchAutomaticScheduler.class));
    }

    @Test
    void failedPollDoesNotDisableNextPoll() {
        var worker=mock(MatchAutomaticWorker.class);
        when(worker.runOnce()).thenThrow(new IllegalStateException("private failure"))
                .thenReturn(new MatchAutomaticWorker.Run(false,0,0,0));
        var scheduler=new MatchAutomaticScheduler(worker);
        assertThatNoException().isThrownBy(scheduler::run);
        assertThatNoException().isThrownBy(scheduler::run);
        verify(worker,times(2)).runOnce();
    }
}
