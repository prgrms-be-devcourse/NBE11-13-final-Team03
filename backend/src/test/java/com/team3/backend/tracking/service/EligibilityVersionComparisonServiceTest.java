package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EligibilityVersionComparisonServiceTest {
    private final EligibilityProfileRepository profiles = mock(EligibilityProfileRepository.class);
    private final EligibilityEvaluator evaluator = mock(EligibilityEvaluator.class);
    private final EligibilityVersionComparisonService service = new EligibilityVersionComparisonService(profiles, evaluator);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    @Test
    void evaluatesBothVersionsOnceWithOneProfileAndOneDateAndPreservesEvidence() {
        var profile = EligibilityProfile.builder().userId(7L).profileVersion(3L).build();
        var before = judgement(EligibilityStatus.NOT_ELIGIBLE);
        var after = judgement(EligibilityStatus.ELIGIBLE);
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(evaluator.judge(profile, List.of(10L, 11L), TODAY)).thenReturn(Map.of(10L, before, 11L, after));

        var result = service.compare(7L, version(10, 1, 1, "v1"), version(11, 2, 1, "v1"), TODAY).orElseThrow();

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.fromVersionId()).isEqualTo(10L);
        assertThat(result.toVersionId()).isEqualTo(11L);
        assertThat(result.profileVersion()).isEqualTo(3L);
        assertThat(result.ruleVersion()).isEqualTo(EligibilityResultService.RULE_VERSION);
        assertThat(result.evaluatedOn()).isEqualTo(TODAY);
        assertThat(result.before()).isSameAs(before);
        assertThat(result.after()).isSameAs(after);
        verify(profiles).findByUserId(7L);
        verify(evaluator).judge(same(profile), eq(List.of(10L, 11L)), eq(TODAY));
        verifyNoMoreInteractions(profiles, evaluator);
    }

    @Test
    void absentProfileIsNotInventedAsUnknown() {
        when(profiles.findByUserId(7L)).thenReturn(Optional.empty());
        assertThat(service.compare(7L, version(10, 1, 1, "v1"), version(11, 2, 1, "v1"), TODAY)).isEmpty();
        verifyNoInteractions(evaluator);
    }

    @Test
    void rejectsNormalizationRuleChangeBeforeReadingProfile() {
        assertThatThrownBy(() -> service.compare(7L, version(10, 1, 1, "v1"), version(11, 2, 1, "v2"), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(profiles, evaluator);
    }

    @Test
    void rejectsDifferentPolicyReversedOrIdenticalVersions() {
        var from = version(10, 1, 1, "v1");
        assertThatThrownBy(() -> service.compare(7L, from, version(11, 2, 2, "v1"), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(7L, version(11, 2, 1, "v1"), from, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(7L, from, from, TODAY)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(profiles, evaluator);
    }

    @Test
    void missingJudgementFailsInsteadOfFabricatingAStatus() {
        var profile = EligibilityProfile.builder().userId(7L).profileVersion(1L).build();
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(evaluator.judge(profile, List.of(10L, 11L), TODAY)).thenReturn(Map.of(10L, judgement(EligibilityStatus.UNKNOWN)));
        assertThatThrownBy(() -> service.compare(7L, version(10, 1, 1, "v1"), version(11, 2, 1, "v1"), TODAY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingUserDateAndUnpersistedVersions() {
        var from = version(10, 1, 1, "v1"); var to = version(11, 2, 1, "v1");
        assertThatThrownBy(() -> service.compare(null, from, to, TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(7L, from, to, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.compare(7L, OpportunityVersion.builder().build(), to, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(profiles, evaluator);
    }

    private EligibilityEvaluator.Judgement judgement(EligibilityStatus status) {
        return new EligibilityEvaluator.Judgement(new EligibilityEvaluation(status, List.of(), "test evidence"), ApplicationStatus.OPEN);
    }

    private OpportunityVersion version(long id, int number, long policyId, String rule) {
        return OpportunityVersion.builder().id(id).versionNo(number)
                .opportunity(Opportunity.builder().id(policyId).build()).normalizationVersion(rule).build();
    }
}
