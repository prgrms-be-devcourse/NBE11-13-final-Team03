package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.tracking.domain.repository.OpportunityVersionTrackingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.team3.backend.tracking.service.OpportunityVersionComparisonTransaction.Outcome.*;

class OpportunityVersionWorkerTest {
    private final OpportunityVersionTrackingRepository tracking = mock(OpportunityVersionTrackingRepository.class);
    private final OpportunityVersionComparisonTransaction comparisons = mock(OpportunityVersionComparisonTransaction.class);
    private final OpportunityVersionWorker worker = new OpportunityVersionWorker(tracking, comparisons);

    @Test
    void boundedKeysetPageProcessesOneStepPerPolicyAndDoesNotConsumeLookahead() {
        when(tracking.findSequentialPolicyIds(10, OpportunityType.YOUTH_POLICY, PageRequest.of(0, 3)))
                .thenReturn(List.of(11L, 15L, 20L));
        when(comparisons.compareNext(11L)).thenReturn(new OpportunityVersionComparisonTransaction.Step(COMPARED, 21L, 31L));
        when(comparisons.compareNext(15L)).thenReturn(new OpportunityVersionComparisonTransaction.Step(WAITING_FOR_BASELINE, 25L, null));
        var result = worker.runOnce(10, 2);
        assertThat(result.lastOpportunityId()).isEqualTo(15);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.attempts()).extracting(OpportunityVersionWorker.Attempt::opportunityId).containsExactly(11L, 15L);
        verify(comparisons, times(1)).compareNext(11L); verify(comparisons, times(1)).compareNext(15L);
        verifyNoMoreInteractions(comparisons);
    }

    @Test
    void failedPolicyDoesNotAbortOtherPoliciesAndNewSweepRetriesIt() {
        when(tracking.findSequentialPolicyIds(0, OpportunityType.YOUTH_POLICY, PageRequest.of(0, 3)))
                .thenReturn(List.of(1L, 2L));
        when(comparisons.compareNext(1L)).thenThrow(new IllegalStateException("not logged"))
                .thenReturn(new OpportunityVersionComparisonTransaction.Step(COMPARED, 3L, 4L));
        when(comparisons.compareNext(2L)).thenReturn(new OpportunityVersionComparisonTransaction.Step(UP_TO_DATE, null, null));
        var first = worker.runOnce(0, 2);
        assertThat(first.hasNext()).isFalse();
        assertThat(first.attempts().getFirst().step()).isEmpty();
        assertThat(first.attempts().getLast().step()).isPresent();
        var second = worker.runOnce(0, 2);
        assertThat(second.attempts().getFirst().step()).isPresent();
        verify(comparisons, times(2)).compareNext(1L);
    }

    @Test
    void emptyPagePreservesCursor() {
        when(tracking.findSequentialPolicyIds(40, OpportunityType.YOUTH_POLICY, PageRequest.of(0, 2))).thenReturn(List.of());
        var result = worker.runOnce(40, 1);
        assertThat(result.lastOpportunityId()).isEqualTo(40);
        assertThat(result.hasNext()).isFalse(); assertThat(result.attempts()).isEmpty();
        verifyNoInteractions(comparisons);
    }

    @Test
    void invalidCursorOrBudgetIsRejectedBeforeDatabaseAccess() {
        assertThatIllegalArgumentException().isThrownBy(() -> worker.runOnce(-1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> worker.runOnce(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> worker.runOnce(0, 501));
        verifyNoInteractions(tracking, comparisons);
    }
}
