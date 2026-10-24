package com.team3.backend.tracking.domain.entity;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.tracking.service.OpportunitySnapshotComparator;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static com.team3.backend.tracking.domain.entity.OpportunityVersionTrackingStatus.*;

class OpportunityVersionTrackingTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private final OpportunitySnapshotComparator comparator = new OpportunitySnapshotComparator();

    @Test
    void explicitBaselineIsTerminalButDoesNotDecideWhichVersionsBelongToBaseline() {
        var baseline = OpportunityVersionTracking.baseline(version(1, 1, 10, "v1"), NOW);
        assertThat(baseline.getStatus()).isEqualTo(BASELINE_SKIPPED);
        assertThat(baseline.getFromVersion()).isNull();
        assertThat(baseline.getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void unchangedVersionIsRecordedWithoutInventingAChangeRow() {
        var from = version(1, 1, 10, "v1"); var to = version(2, 2, 10, "v1");
        var comparison = comparator.compare(Map.of(), "v1", Map.of(), "v1");
        var record = OpportunityVersionTracking.compared(from, to, comparison, NOW);
        assertThat(record.getStatus()).isEqualTo(NO_CHANGE);
        assertThat(record.getProcessedAt()).isEqualTo(NOW);
        assertThat(record.getFromVersion()).isSameAs(from);
        assertThat(record.getOpportunityVersion()).isSameAs(to);
        assertThatThrownBy(() -> OpportunityChange.record(from, to, comparison, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changedVersionStaysPendingUntilEventAssessmentIsDurable() {
        var from = version(1, 1, 10, "v1"); var to = version(2, 2, 10, "v1");
        var comparison = comparator.compare(Map.of("title", "이전"), "v1", Map.of("title", "변경"), "v1");
        var record = OpportunityVersionTracking.compared(from, to, comparison, NOW);
        var change = OpportunityChange.record(from, to, comparison, NOW);
        assertThat(record.getStatus()).isEqualTo(READY_FOR_EVENT_ANALYSIS);
        assertThat(record.getProcessedAt()).isNull();
        assertThat(change.getFromVersion()).isSameAs(from);
        assertThat(change.getToVersion()).isSameAs(to);
        assertThat(change.getDifferences()).isEqualTo(comparison.differences());
        record.completeEventAnalysis(NOW.plusSeconds(1));
        assertThat(record.getStatus()).isEqualTo(PROCESSED);
        assertThat(record.getProcessedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThatThrownBy(() -> record.completeEventAnalysis(NOW.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changedNormalizationRuleIsRecordedAsSuppressedEvenWhenDataAlsoChanges() {
        var from = version(1, 1, 10, "v1"); var to = version(2, 2, 10, "v2");
        var comparison = comparator.compare(Map.of("age", 34), "v1", Map.of("age", 39), "v2");
        var record = OpportunityVersionTracking.compared(from, to, comparison, NOW);
        assertThat(record.getStatus()).isEqualTo(NORMALIZATION_VERSION_CHANGED);
        assertThat(record.getProcessedAt()).isEqualTo(NOW);
        assertThat(OpportunityChange.record(from, to, comparison, NOW).getDifferences()).hasSize(1);
        assertThatThrownBy(() -> record.completeEventAnalysis(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnorderedVersionsDifferentOwnersAndInconsistentNormalizationOutcome() {
        var from = version(1, 1, 10, "v1"); var to = version(2, 2, 10, "v1");
        var comparison = comparator.compare(Map.of(), "v1", Map.of(), "v1");
        assertThatThrownBy(() -> OpportunityVersionTracking.compared(to, from, comparison, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpportunityVersionTracking.compared(from, from, comparison, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpportunityVersionTracking.compared(from, version(3, 2, 11, "v1"), comparison, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpportunityVersionTracking.compared(from, version(3, 2, 10, "v2"), comparison, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpportunityVersionTracking.baseline(OpportunityVersion.builder().build(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCompletionBeforeComparisonWithoutMutatingState() {
        var from = version(1, 1, 10, "v1"); var to = version(2, 2, 10, "v1");
        var result = comparator.compare(Map.of(), "v1", Map.of("title", "변경"), "v1");
        var record = OpportunityVersionTracking.compared(from, to, result, NOW);
        assertThatThrownBy(() -> record.completeEventAnalysis(NOW.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(record.getStatus()).isEqualTo(READY_FOR_EVENT_ANALYSIS);
        assertThat(record.getProcessedAt()).isNull();
    }

    private OpportunityVersion version(long id, int versionNo, long opportunityId, String normalizationVersion) {
        return OpportunityVersion.builder().id(id).versionNo(versionNo)
                .opportunity(Opportunity.builder().id(opportunityId).build())
                .normalizationVersion(normalizationVersion).normalizedSnapshot(Map.of()).build();
    }
}
