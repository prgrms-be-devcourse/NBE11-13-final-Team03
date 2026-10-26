package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.VersionTrackingProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static com.team3.backend.tracking.service.OpportunityVersionComparisonTransaction.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VersionTrackingPipelineTest {
    private final OpportunityVersionWorker versions = mock(OpportunityVersionWorker.class);
    private final OpportunityVersionAnalysisTransaction analyses = mock(OpportunityVersionAnalysisTransaction.class);
    private final VersionTrackingPipeline pipeline = new VersionTrackingPipeline(versions, analyses, new VersionTrackingProperties(true, 2, 60000));

    @Test
    void fairSweepResumesAfterWaitingBaselinePolicyAndResetsAtEnd() {
        when(versions.runOnce(0, 2)).thenReturn(new OpportunityVersionWorker.Batch(2, true,
                List.of(attempt(1, WAITING_FOR_BASELINE), attempt(2, COMPARED))));
        when(versions.runOnce(2, 2)).thenReturn(new OpportunityVersionWorker.Batch(3, false, List.of(attempt(3, WAITING_FOR_EVENT_ANALYSIS))));
        var first = pipeline.runOnce();
        assertThat(first.nextCursor()).isEqualTo(2); assertThat(first.visitedPolicies()).isEqualTo(2);
        assertThat(first.analysisCalls()).isEqualTo(1);
        assertThat(pipeline.runOnce().nextCursor()).isZero();
        verify(analyses).analyze(20L); verify(analyses).analyze(30L); verifyNoMoreInteractions(analyses);
    }

    @Test
    void failedAnalysisDoesNotRollbackComparisonOrAbortOtherPolicies() {
        when(versions.runOnce(0, 2)).thenReturn(new OpportunityVersionWorker.Batch(2, false, List.of(attempt(1, COMPARED), attempt(2, COMPARED))));
        when(analyses.analyze(10L)).thenThrow(new IllegalStateException("storage failure"));
        var result = pipeline.runOnce();
        assertThat(result.failures()).isEqualTo(1); assertThat(result.analysisCalls()).isEqualTo(2);
        verify(analyses).analyze(20L);
        pipeline.runOnce(); verify(analyses, times(2)).analyze(10L);
    }

    @Test
    void failedComparisonAndNonReadyOutcomesDoNotInvokeAnalysis() {
        when(versions.runOnce(0, 2)).thenReturn(new OpportunityVersionWorker.Batch(2, false,
                List.of(new OpportunityVersionWorker.Attempt(1, Optional.empty()), attempt(2, UP_TO_DATE))));
        assertThat(pipeline.runOnce().failures()).isEqualTo(1);
        verifyNoInteractions(analyses);
    }

    @Test
    void automaticFirstVersionBaselineDoesNotInvokeEventAnalysis() {
        when(versions.runOnce(0,2)).thenReturn(new OpportunityVersionWorker.Batch(1,false,
                List.of(attempt(1,BASELINE_RECORDED))));
        var result=pipeline.runOnce();
        assertThat(result.analysisCalls()).isZero(); assertThat(result.failures()).isZero();
        verifyNoInteractions(analyses);
    }

    @Test
    void discoveryFailureKeepsCursorAndReleasesRunGuard() {
        when(versions.runOnce(0, 2)).thenReturn(new OpportunityVersionWorker.Batch(2, true, List.of()));
        pipeline.runOnce();
        when(versions.runOnce(2, 2)).thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(new OpportunityVersionWorker.Batch(2, false, List.of()));
        assertThatThrownBy(pipeline::runOnce).isInstanceOf(IllegalStateException.class);
        var recovered = pipeline.runOnce(); assertThat(recovered.alreadyRunning()).isFalse(); assertThat(recovered.nextCursor()).isZero();
        verify(versions, times(2)).runOnce(2, 2);
    }

    @Test
    void overlappingInvocationDoesNotDuplicateWork() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(versions.runOnce(0, 2)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return new OpportunityVersionWorker.Batch(0, false, List.of());
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(pipeline::runOnce);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pipeline.runOnce().alreadyRunning()).isTrue();
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).alreadyRunning()).isFalse();
        }
        verify(versions, times(1)).runOnce(0, 2);
    }

    private OpportunityVersionWorker.Attempt attempt(long policyId, OpportunityVersionComparisonTransaction.Outcome outcome) {
        return new OpportunityVersionWorker.Attempt(policyId, Optional.of(new OpportunityVersionComparisonTransaction.Step(outcome, policyId * 10, policyId * 100)));
    }
}
