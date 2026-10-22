package com.team3.backend.opportunity.conditionset;

import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.RequirementType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.dto.ConditionRow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionSetKeyTest {

    @Test
    void 조건_순서와_지역_코드_순서는_키에_영향이_없다() {
        List<OpportunityCondition> a = List.of(age(19, 34), region("SPECIFIC"));
        List<OpportunityCondition> b = List.of(region("SPECIFIC"), age(19, 34));

        assertThat(ConditionSetKey.hash(a, List.of("1168000000", "1144000000")))
                .isEqualTo(ConditionSetKey.hash(b, List.of("1144000000", "1168000000")));
    }

    @Test
    void 거주지역_코드나_조건_값이_다르면_키가_다르다() {
        String base = ConditionSetKey.hash(List.of(age(19, 34)), List.of("1168000000"));

        assertThat(ConditionSetKey.hash(List.of(age(19, 34)), List.of("1144000000"))).isNotEqualTo(base);
        assertThat(ConditionSetKey.hash(List.of(age(19, 39)), List.of("1168000000"))).isNotEqualTo(base);
        assertThat(ConditionSetKey.hash(List.of(age(19, 34)), List.of())).isNotEqualTo(base);
    }

    @Test
    void 검증_상태가_다르면_키가_다르다() {
        OpportunityCondition valid = age(19, 34);
        OpportunityCondition conflict = OpportunityCondition.builder()
                .conditionType(ConditionType.AGE).requirementType(RequirementType.REQUIRED).conditionField("age")
                .expression(valid.getExpression()).validationStatus(ValidationStatus.DATA_CONFLICT).build();

        assertThat(ConditionSetKey.hash(List.of(valid), List.of()))
                .isNotEqualTo(ConditionSetKey.hash(List.of(conflict), List.of()));
    }

    @Test
    void 근거_evidence는_키에_들어가지_않는다() {
        OpportunityCondition withEvidence = OpportunityCondition.builder()
                .conditionType(ConditionType.AGE).requirementType(RequirementType.REQUIRED).conditionField("age")
                .expression(Map.of("status", "RANGE", "min", 19, "max", 34))
                .evidence(Map.of("evidenceText", "만 19세~34세", "sourceType", "YOUTH_CENTER_API"))
                .validationStatus(ValidationStatus.VALID).build();

        assertThat(ConditionSetKey.hash(List.of(withEvidence), List.of()))
                .isEqualTo(ConditionSetKey.hash(List.of(age(19, 34)), List.of()));
    }

    @Test
    void 메모리의_조건과_DB에서_읽은_jsonb_조건은_같은_키를_낸다() {
        Map<String, Object> inMemory = new LinkedHashMap<>();
        inMemory.put("status", "RANGE");
        inMemory.put("min", 19);
        inMemory.put("max", 34L);
        inMemory.put("values", List.of("A", "B"));
        OpportunityCondition entity = OpportunityCondition.builder()
                .conditionType(ConditionType.AGE).requirementType(RequirementType.REQUIRED).conditionField("age")
                .expression(inMemory).validationStatus(ValidationStatus.VALID).build();

        Map<String, Object> fromDb = new LinkedHashMap<>();
        fromDb.put("values", List.of("A", "B"));
        fromDb.put("max", 34.0);
        fromDb.put("status", "RANGE");
        fromDb.put("min", 19);
        ConditionRow row = new ConditionRow(1L, ConditionType.AGE, RequirementType.REQUIRED, "age", fromDb,
                ValidationStatus.VALID);

        assertThat(ConditionSetKey.hash(List.of(entity), List.of("1168000000")))
                .isEqualTo(ConditionSetKey.hashRows(List.of(row), List.of("1168000000")));
    }

    @Test
    void 정규_형식은_키_규칙_버전으로_시작한다() {
        assertThat(ConditionSetKey.canonical(List.of(), List.of())).startsWith("v" + ConditionSetKey.VERSION + "\n");
    }

    @Test
    void 목록_순서는_유지해서_비교한다() {
        assertThat(ConditionSetKey.canonicalValue(List.of("A", "B")))
                .isNotEqualTo(ConditionSetKey.canonicalValue(List.of("B", "A")));
        assertThat(ConditionSetKey.canonicalValue(Map.of("b", 1, "a", "x"))).isEqualTo("{\"a\":\"x\",\"b\":1}");
    }

    private static OpportunityCondition age(int min, int max) {
        return OpportunityCondition.builder()
                .conditionType(ConditionType.AGE).requirementType(RequirementType.REQUIRED).conditionField("age")
                .expression(Map.of("status", "RANGE", "min", min, "max", max))
                .validationStatus(ValidationStatus.VALID).build();
    }

    private static OpportunityCondition region(String status) {
        return OpportunityCondition.builder()
                .conditionType(ConditionType.REGION).requirementType(RequirementType.REQUIRED)
                .conditionField("residenceRegion")
                .expression(Map.of("status", status, "role", "RESIDENCE"))
                .validationStatus(ValidationStatus.VALID).build();
    }
}
