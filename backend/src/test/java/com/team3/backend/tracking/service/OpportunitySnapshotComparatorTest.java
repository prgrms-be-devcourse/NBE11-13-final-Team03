package com.team3.backend.tracking.service;

import com.team3.backend.tracking.domain.model.SnapshotComparison;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.team3.backend.tracking.domain.model.SnapshotComparison.Outcome.*;

class OpportunitySnapshotComparatorTest {
    private final OpportunitySnapshotComparator comparator = new OpportunitySnapshotComparator();

    @Test
    void objectKeyOrderAndJsonNumberRepresentationDoNotCreateChanges() {
        var before = new LinkedHashMap<String, Object>();
        before.put("title", "정책"); before.put("age", Map.of("minAge", 19, "maxAge", 34L));
        var after = new LinkedHashMap<String, Object>();
        after.put("age", Map.of("maxAge", new BigDecimal("34.00"), "minAge", 19.0)); after.put("title", "정책");
        var result = compare(before, after);
        assertThat(result.outcome()).isEqualTo(NO_CHANGE);
        assertThat(result.differences()).isEmpty();
    }

    @Test
    void recordsNestedFieldsInStableOrderAndSeparatesApplicationAndBusinessPeriods() {
        var result = compare(Map.of("businessPeriod", Map.of("endDate", "2026-12-31"),
                        "applicationPeriods", List.of(Map.of("endDate", "2026-10-26")), "age", Map.of("maxAge", 34)),
                Map.of("businessPeriod", Map.of("endDate", "2027-01-31"),
                        "applicationPeriods", List.of(Map.of("endDate", "2026-11-01")), "age", Map.of("maxAge", 39)));
        assertThat(result.outcome()).isEqualTo(CHANGES_DETECTED);
        assertThat(result.differences()).extracting(change -> change.path())
                .containsExactly("/age/maxAge", "/applicationPeriods", "/businessPeriod/endDate");
        assertThat(result.differences().getFirst().before()).isEqualTo(new BigDecimal("34"));
        assertThat(result.differences().getFirst().after()).isEqualTo(new BigDecimal("39"));
    }

    @Test
    void preservesMissingVersusExplicitNullInBothDirections() {
        var explicitNull = new LinkedHashMap<String, Object>(); explicitNull.put("income", null);
        var added = compare(Map.of(), explicitNull).differences().getFirst();
        assertThat(added.path()).isEqualTo("/income");
        assertThat(added.beforePresent()).isFalse(); assertThat(added.before()).isNull();
        assertThat(added.afterPresent()).isTrue(); assertThat(added.after()).isNull();
        var removed = compare(explicitNull, Map.of()).differences().getFirst();
        assertThat(removed.beforePresent()).isTrue(); assertThat(removed.afterPresent()).isFalse();
        assertThat(compare(explicitNull, explicitNull).outcome()).isEqualTo(NO_CHANGE);
    }

    @Test
    void recordsTypeChangesWithoutTreatingStringsBooleansOrMissingAsEquivalentNumbers() {
        assertThat(compare(Map.of("value", 1), Map.of("value", "1")).outcome()).isEqualTo(CHANGES_DETECTED);
        assertThat(compare(Map.of("value", true), Map.of("value", "true")).outcome()).isEqualTo(CHANGES_DETECTED);
        assertThat(compare(Map.of("value", Map.of()), Map.of()).outcome()).isEqualTo(CHANGES_DETECTED);
    }

    @Test
    void preservesArrayOrderAndNullElementsWithoutInventingDomainMeaning() {
        var result = compare(Map.of("region", Map.of("codes", List.of("11", "26"))),
                Map.of("region", Map.of("codes", List.of("26", "11"))));
        assertThat(result.differences()).hasSize(1);
        assertThat(result.differences().getFirst().path()).isEqualTo("/region/codes");
        assertThat(compare(Map.of("items", Arrays.asList("a", null)), Map.of("items", Arrays.asList("a", null)))
                .outcome()).isEqualTo(NO_CHANGE);
    }

    @Test
    void normalizationRuleChangeSuppressesAssessmentEvenWhenSnapshotsAreEqual() {
        var equal = comparator.compare(Map.of("title", "정책"), "v1", Map.of("title", "정책"), "v2");
        assertThat(equal.outcome()).isEqualTo(NORMALIZATION_VERSION_CHANGED);
        assertThat(equal.differences()).isEmpty();
        var changed = comparator.compare(Map.of("title", "이전"), "v1", Map.of("title", "변경"), "v2");
        assertThat(changed.outcome()).isEqualTo(NORMALIZATION_VERSION_CHANGED);
        assertThat(changed.differences()).hasSize(1); // Actual audit difference is not lost on suppression.
    }

    @Test
    void escapesJsonPointerKeysAndKeepsWhitespaceAndUnknownFieldsAsAuditEvidence() {
        var result = compare(Map.of("a/b~c", "값", "title", "정책"),
                Map.of("a/b~c", "변경", "title", " 정책 ", "futureField", true));
        assertThat(result.differences()).extracting(change -> change.path())
                .containsExactly("/a~1b~0c", "/futureField", "/title");
    }

    @Test
    void comparisonOwnsImmutableCopiesAndDoesNotMutateInputs() {
        var oldItems = new ArrayList<Object>(List.of(Map.of("endDate", "2026-10-26")));
        var newItems = new ArrayList<Object>(List.of(Map.of("endDate", "2026-11-01")));
        var before = new LinkedHashMap<String, Object>(); before.put("applicationPeriods", oldItems);
        var after = new LinkedHashMap<String, Object>(); after.put("applicationPeriods", newItems);
        var result = compare(before, after);
        oldItems.clear(); newItems.clear();
        assertThat((List<?>) result.differences().getFirst().before()).hasSize(1);
        assertThat((List<?>) result.differences().getFirst().after()).hasSize(1);
        assertThatThrownBy(() -> result.differences().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) result.differences().getFirst().before()).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingNormalizationMetadataAndNonJsonOrNonFiniteValues() {
        assertThatThrownBy(() -> comparator.compare(Map.of(), "", Map.of(), "v1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comparator.compare(Map.of(), "v1", Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compare(Map.of("value", Double.NaN), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compare(Map.of("value", LocalDate.of(2026, 10, 25)), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outcomeAndDifferenceListMustAgree() {
        assertThatThrownBy(() -> new SnapshotComparison(CHANGES_DETECTED, List.of())).isInstanceOf(IllegalArgumentException.class);
        var differences = compare(Map.of(), Map.of("title", "신규")).differences();
        assertThatThrownBy(() -> new SnapshotComparison(NO_CHANGE, differences)).isInstanceOf(IllegalArgumentException.class);
    }

    private SnapshotComparison compare(Map<String, Object> before, Map<String, Object> after) {
        return comparator.compare(before, "youth-v1", after, "youth-v1");
    }
}
