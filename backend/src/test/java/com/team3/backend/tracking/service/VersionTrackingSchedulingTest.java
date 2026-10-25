package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.VersionTrackingConfig;
import com.team3.backend.tracking.config.VersionTrackingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VersionTrackingSchedulingTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(VersionTrackingConfig.class, VersionTrackingPipeline.class, VersionTrackingScheduler.class)
            .withBean(OpportunityVersionWorker.class, () -> mock(OpportunityVersionWorker.class))
            .withBean(OpportunityVersionAnalysisTransaction.class, () -> mock(OpportunityVersionAnalysisTransaction.class));

    @Test
    void defaultsAreOffAndBudgeted() {
        context.run(result -> {
            assertThat(result).hasNotFailed().doesNotHaveBean(VersionTrackingPipeline.class).doesNotHaveBean(VersionTrackingScheduler.class);
            var properties = result.getBean(VersionTrackingProperties.class);
            assertThat(properties.enabled()).isFalse(); assertThat(properties.maxPoliciesPerRun()).isEqualTo(25);
            assertThat(properties.pollIntervalMs()).isEqualTo(60000);
        });
    }

    @Test
    void versionFlagAloneCannotRegisterAnalysisPipelineOrScheduler() {
        context.withPropertyValues("yon.tracking.version.enabled=true").run(result ->
                assertThat(result).hasNotFailed().doesNotHaveBean(VersionTrackingPipeline.class).doesNotHaveBean(VersionTrackingScheduler.class));
    }

    @Test
    void analysisFlagAloneCannotRegisterPipelineOrScheduler() {
        context.withPropertyValues("yon.tracking.version-analysis.enabled=true").run(result ->
                assertThat(result).hasNotFailed().doesNotHaveBean(VersionTrackingPipeline.class).doesNotHaveBean(VersionTrackingScheduler.class));
    }

    @Test
    void globalSchedulingOffKeepsManualPipelineButPreventsSchedulerRegistration() {
        context.withPropertyValues("yon.tracking.version.enabled=true", "yon.tracking.version-analysis.enabled=true", "app.scheduling.enabled=false")
                .run(result -> assertThat(result).hasNotFailed().hasSingleBean(VersionTrackingPipeline.class).doesNotHaveBean(VersionTrackingScheduler.class));
    }

    @Test
    void bothFlagsRegisterSchedulerWithoutChangingOtherFlags() {
        context.withPropertyValues("yon.tracking.version.enabled=true", "yon.tracking.version-analysis.enabled=true")
                .run(result -> assertThat(result).hasNotFailed().hasSingleBean(VersionTrackingScheduler.class).hasSingleBean(VersionTrackingPipeline.class));
    }

    @Test
    void invalidBudgetOrPollIntervalFailsBinding() {
        context.withPropertyValues("yon.tracking.version.max-policies-per-run=0").run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("yon.tracking.version.poll-interval-ms=999").run(result -> assertThat(result).hasFailed());
    }

    @Test
    void pollingFailureDoesNotPreventNextInvocation() {
        var pipeline = mock(VersionTrackingPipeline.class);
        var scheduler = new VersionTrackingScheduler(pipeline);
        when(pipeline.runOnce()).thenThrow(new IllegalStateException("failure"))
                .thenReturn(new VersionTrackingPipeline.Run(false, 0, 0, 0, 0));
        assertThatNoException().isThrownBy(scheduler::run);
        assertThatNoException().isThrownBy(scheduler::run);
        verify(pipeline, times(2)).runOnce();
    }
}
