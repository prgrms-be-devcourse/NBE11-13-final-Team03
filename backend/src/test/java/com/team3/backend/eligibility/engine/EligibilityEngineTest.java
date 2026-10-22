package com.team3.backend.eligibility.engine;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.region.directory.RegionIndex.Entry;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.region.domain.entity.Region;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.team3.backend.eligibility.domain.entity.ConditionMatchResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class EligibilityEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final EligibilityEngine engine = new EligibilityEngine();
    private final Region seoul = Region.builder().regionCode("1100000000").regionName("서울특별시").build();
    private final Region gangnam = Region.builder().regionCode("1168000000").regionName("강남구").parent(seoul).build();
    private final RegionIndex index = new RegionIndex(List.of(
            new Entry("1100000000", "서울특별시", null),
            new Entry("1168000000", "강남구", "1100000000"),
            new Entry("1144000000", "마포구", "1100000000")));

    // ── 연령·지역 ──

    @Test
    void 연령과_지역을_모두_충족하면_ELIGIBLE() {
        EligibilityEvaluation r = evaluate(profile(), List.of(age(19, 34), region("SPECIFIC")), List.of("1100000000"));

        assertThat(r.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(r.conditions()).extracting(ConditionEvaluation::result).containsOnly(MATCHED);
        assertThat(r.conditions().get(0).actual()).isEqualTo(28); // 1998-05-15생, 기준일 2026-10-15
    }

    @Test
    void 상위_지역이_정책_지역이면_충족_다른_구면_불충족() {
        assertThat(evaluate(profile(), List.of(region("SPECIFIC")), List.of("1100000000")).conditions().get(0).result())
                .isEqualTo(MATCHED);
        assertThat(evaluate(profile(), List.of(region("SPECIFIC")), List.of("1144000000")).status())
                .isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
        assertThat(evaluate(profile(), List.of(region("NATIONAL")), List.of()).status())
                .isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test
    void 연령_초과면_NOT_ELIGIBLE() {
        assertThat(evaluate(profile(), List.of(age(19, 24)), List.of()).status()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
    }

    @Test
    void 영영Y처럼_정책_연령이_모순이면_UNKNOWN() {
        EligibilityConditionInput conflict = condition(ConditionType.AGE, RequirementType.REQUIRED,
                Map.of("status", "UNKNOWN"), ValidationStatus.DATA_CONFLICT);

        EligibilityEvaluation r = evaluate(profile(), List.of(conflict), List.of());

        assertThat(r.conditions().get(0).result()).isEqualTo(DATA_UNCLEAR);
        assertThat(r.status()).isEqualTo(EligibilityStatus.UNKNOWN);
    }

    @Test
    void 정책에_연령_정보_자체가_없으면_ELIGIBLE을_확정하지_않는다() {
        EligibilityConditionInput missing = condition(ConditionType.AGE, RequirementType.REQUIRED,
                Map.of("status", "UNKNOWN"), ValidationStatus.UNKNOWN);

        EligibilityEvaluation r = evaluate(profile(), List.of(missing), List.of());

        assertThat(r.conditions().get(0).result()).isEqualTo(CHECK_REQUIRED);
        assertThat(r.status()).isEqualTo(EligibilityStatus.POSSIBLY_ELIGIBLE);
    }

    @Test
    void 공식_출처로_확인_못_한_연령은_범위_안이면_확인_필요_밖이면_불확실() {
        EligibilityEvaluation inside = evaluate(profile(), List.of(unverifiedAge(19, 34)), List.of());
        EligibilityEvaluation outside = evaluate(profile(), List.of(unverifiedAge(19, 24)), List.of());

        assertThat(inside.conditions().get(0).result()).isEqualTo(CHECK_REQUIRED);
        assertThat(inside.status()).isEqualTo(EligibilityStatus.POSSIBLY_ELIGIBLE);
        assertThat(outside.conditions().get(0).result()).isEqualTo(DATA_UNCLEAR);
        assertThat(outside.status()).isEqualTo(EligibilityStatus.UNKNOWN);
    }

    @Test
    void 공식_출처로_확인_못_한_조건만으로는_ELIGIBLE이나_NOT_ELIGIBLE이_되지_않는다() {
        EligibilityConditionInput region = condition(ConditionType.REGION, RequirementType.REQUIRED,
                Map.of("status", "SPECIFIC", "role", "RESIDENCE"), ValidationStatus.UNVERIFIED);

        assertThat(evaluate(profile(), List.of(unverifiedAge(19, 34), region), List.of("1100000000")).status())
                .isEqualTo(EligibilityStatus.POSSIBLY_ELIGIBLE);
        assertThat(evaluate(profile(), List.of(unverifiedAge(19, 24), region), List.of("1144000000")).status())
                .isEqualTo(EligibilityStatus.UNKNOWN);
    }

    @Test
    void 공식_출처로_확인_못_한_연령이_있어도_확인된_다른_조건이_불충족이면_NOT_ELIGIBLE() {
        assertThat(evaluate(profile(), List.of(unverifiedAge(19, 34), region("SPECIFIC")), List.of("1144000000")).status())
                .isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
    }

    // ── 집계 우선순위 ──

    @Test
    void 불충족이_데이터_불확실보다_우선하고_데이터_불확실이_입력_누락보다_우선한다() {
        EligibilityConditionInput unclear = condition(ConditionType.AGE, RequirementType.REQUIRED,
                Map.of("status", "UNKNOWN"), ValidationStatus.DATA_CONFLICT);
        EligibilityConditionInput notMatched = region("SPECIFIC");
        EligibilityConditionInput userMissing = income("MEDIAN_PERCENT", null, 150, null, null, ValidationStatus.VALID);

        assertThat(evaluate(profile(), List.of(unclear, notMatched), List.of("1144000000")).status())
                .isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
        assertThat(evaluate(profile(), List.of(unclear, userMissing), List.of()).status())
                .isEqualTo(EligibilityStatus.UNKNOWN);
        assertThat(evaluate(profile(), List.of(age(19, 34), userMissing), List.of()).status())
                .isEqualTo(EligibilityStatus.POSSIBLY_ELIGIBLE);
    }

    @Test
    void 우대조건은_판정하지_않고_비어_있는_코드_조건은_집계에서_뺀다() {
        EligibilityConditionInput preferred = condition(ConditionType.EMPLOYMENT, RequirementType.PREFERRED,
                Map.of("status", "SPECIFIC", "values", List.of("EMPLOYED")), ValidationStatus.VALID);
        EligibilityConditionInput blankCode = condition(ConditionType.MARITAL, RequirementType.REQUIRED,
                Map.of("status", "UNKNOWN", "values", List.of()), ValidationStatus.UNKNOWN);

        EligibilityEvaluation r = evaluate(profile(), List.of(age(19, 34), preferred, blankCode), List.of());

        assertThat(r.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(r.conditions()).extracting(ConditionEvaluation::result).containsExactly(MATCHED, NOT_SPECIFIED);
    }

    // ── 코드 조건 ──

    @Test
    void 코드_조건은_하나라도_일치하면_충족_기타가_있으면_확인_필요() {
        assertThat(result(code(ConditionType.EMPLOYMENT, "UNEMPLOYED", "STARTUP_PREPARING"))).isEqualTo(MATCHED);
        assertThat(result(code(ConditionType.EMPLOYMENT, "EMPLOYED"))).isEqualTo(NOT_MATCHED);
        assertThat(result(code(ConditionType.EMPLOYMENT, "EMPLOYED", "OTHER"))).isEqualTo(CHECK_REQUIRED);
    }

    @Test
    void 코드표에_없는_코드가_섞인_조건은_일치하지_않으면_데이터_불확실() {
        EligibilityConditionInput review = condition(ConditionType.EMPLOYMENT, RequirementType.REQUIRED,
                Map.of("status", "SPECIFIC", "values", List.of("EMPLOYED")), ValidationStatus.NEEDS_REVIEW);

        assertThat(result(review)).isEqualTo(DATA_UNCLEAR);
    }

    @Test
    void 학력은_최종학력과_학적상태로_비교하고_휴학은_확인_필요() {
        assertThat(result(code(ConditionType.EDUCATION, "UNIVERSITY_GRADUATED"))).isEqualTo(MATCHED);
        assertThat(result(code(ConditionType.EDUCATION, "UNIVERSITY_ENROLLED"))).isEqualTo(NOT_MATCHED);

        EligibilityProfile onLeave = profileBuilder().enrollmentStatus(EnrollmentStatus.LEAVE_OF_ABSENCE).build();
        assertThat(evaluate(onLeave, List.of(code(ConditionType.EDUCATION, "UNIVERSITY_ENROLLED")), List.of())
                .conditions().get(0).result()).isEqualTo(CHECK_REQUIRED);
    }

    @Test
    void 프로필에_없거나_모름이면_입력_누락() {
        assertThat(result(code(ConditionType.MARITAL, "SINGLE"))).isEqualTo(USER_INFO_MISSING);

        EligibilityProfile unknownMarital = profileBuilder().maritalStatus(MaritalStatus.UNKNOWN).build();
        assertThat(evaluate(unknownMarital, List.of(code(ConditionType.MARITAL, "SINGLE")), List.of())
                .conditions().get(0).result()).isEqualTo(USER_INFO_MISSING);
    }

    // ── 소득 ──

    @Test
    void 기준중위소득_비율을_비교한다() {
        EligibilityProfile p = profileBuilder()
                .medianIncomeStatus(AnswerStatus.PROVIDED).medianIncomePercent(new BigDecimal("100")).build();

        assertThat(evaluate(p, List.of(income("MEDIAN_PERCENT", null, 150, null, "HOUSEHOLD", ValidationStatus.VALID)), List.of())
                .conditions().get(0).result()).isEqualTo(MATCHED);
        assertThat(evaluate(p, List.of(income("MEDIAN_PERCENT", null, 80, null, "HOUSEHOLD", ValidationStatus.VALID)), List.of())
                .conditions().get(0).result()).isEqualTo(NOT_MATCHED);
    }

    @Test
    void 금액은_월_연을_환산하고_경계_포함_여부를_지킨다() {
        // 연 2,400만 원 → 월 200만 원
        EligibilityProfile p = profileBuilder()
                .personalIncome(IncomeAnswer.provided(new BigDecimal("24000000"), IncomePeriod.YEARLY)).build();

        assertThat(evaluate(p, List.of(amount(2_500_000, true, "INDIVIDUAL", "MONTHLY")), List.of())
                .conditions().get(0).result()).isEqualTo(MATCHED);
        assertThat(evaluate(p, List.of(amount(2_000_000, false, "INDIVIDUAL", "MONTHLY")), List.of())
                .conditions().get(0).result()).isEqualTo(NOT_MATCHED); // 200만 원 "미만" 기준
    }

    @Test
    void 개인_가구_기준이_불명확하면_확인_필요_단위_미확인이면_데이터_불확실() {
        EligibilityProfile p = profileBuilder()
                .personalIncome(IncomeAnswer.provided(new BigDecimal("2000000"), IncomePeriod.MONTHLY)).build();

        assertThat(evaluate(p, List.of(amount(2_500_000, true, "UNKNOWN", "MONTHLY")), List.of())
                .conditions().get(0).result()).isEqualTo(CHECK_REQUIRED);

        Map<String, Object> unconfirmed = new HashMap<>(Map.of("status", "MAX_ONLY", "type", "AMOUNT", "max", 5000,
                "unitConfirmed", false));
        EligibilityConditionInput apiAmount = condition(ConditionType.INCOME, RequirementType.REQUIRED, unconfirmed,
                ValidationStatus.NEEDS_REVIEW);
        assertThat(evaluate(p, List.of(apiAmount), List.of()).conditions().get(0).result()).isEqualTo(DATA_UNCLEAR);
    }

    // ── 자유텍스트 ──

    @Test
    void 추가_자격과_참여_제한_원문은_확인_필요로_POSSIBLY() {
        EligibilityConditionInput qualification = condition(ConditionType.OTHER, RequirementType.REQUIRED,
                Map.of("status", "UNSTRUCTURED"), ValidationStatus.NEEDS_REVIEW);
        EligibilityConditionInput restriction = condition(ConditionType.OTHER, RequirementType.EXCLUSION,
                Map.of("status", "UNSTRUCTURED"), ValidationStatus.NEEDS_REVIEW);

        EligibilityEvaluation r = evaluate(profile(), List.of(age(19, 34), qualification, restriction), List.of());

        assertThat(r.status()).isEqualTo(EligibilityStatus.POSSIBLY_ELIGIBLE);
        assertThat(r.conditions()).extracting(ConditionEvaluation::result).containsExactly(MATCHED, CHECK_REQUIRED, CHECK_REQUIRED);
    }

    @Test
    void 조건이_하나도_없으면_ELIGIBLE이_아니라_UNKNOWN() {
        EligibilityEvaluation r = evaluate(profile(), List.of(), List.of());

        assertThat(r.status()).isEqualTo(EligibilityStatus.UNKNOWN);
        assertThat(r.conditions()).isEmpty();
    }

    @Test
    void 검증_오류는_원문_확인_필요보다_먼저_반영된다() {
        EligibilityConditionInput conflicted = condition(ConditionType.OTHER, RequirementType.REQUIRED,
                Map.of("status", "UNSTRUCTURED"), ValidationStatus.DATA_CONFLICT);

        EligibilityEvaluation r = evaluate(profile(), List.of(age(19, 34), conflicted), List.of());

        assertThat(r.status()).isEqualTo(EligibilityStatus.UNKNOWN);
        assertThat(r.conditions()).extracting(ConditionEvaluation::result).containsExactly(MATCHED, DATA_UNCLEAR);
    }

    @Test
    void 검증_오류는_NOT_SPECIFIED보다_먼저_반영된다() {
        EligibilityConditionInput invalid = condition(ConditionType.MARITAL, RequirementType.REQUIRED,
                Map.of("status", "UNKNOWN"), ValidationStatus.INVALID);
        ConditionEvaluation notSpecified = new ConditionEvaluation(ConditionType.MARITAL, "marital",
                RequirementType.REQUIRED, null, null, null, NOT_SPECIFIED, "정책 데이터에 이 조건 정보가 없습니다.");

        assertThat(EligibilityEngine.applyValidation(invalid, notSpecified).result()).isEqualTo(DATA_UNCLEAR);
    }

    // ── 직업훈련 ──

    @Test
    void 내일배움카드_과정은_카드_발급_상태로_판정한다() {
        EligibilityConditionInput card = condition(ConditionType.TRAINING_CARD, RequirementType.REQUIRED,
                Map.of("status", "CARD_REQUIRED"), ValidationStatus.VALID);

        assertThat(evaluate(profileBuilder().trainingCardStatus(TrainingCardStatus.ISSUED).build(), List.of(card), List.of())
                .status()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(resultWith(profileBuilder().trainingCardStatus(TrainingCardStatus.APPLYING).build(), card))
                .isEqualTo(CHECK_REQUIRED);
        assertThat(resultWith(profileBuilder().trainingCardStatus(TrainingCardStatus.NOT_ISSUED).build(), card))
                .isEqualTo(CHECK_REQUIRED);
        assertThat(resultWith(profileBuilder().trainingCardStatus(TrainingCardStatus.UNKNOWN).build(), card))
                .isEqualTo(USER_INFO_MISSING);
        assertThat(resultWith(profile(), card)).isEqualTo(USER_INFO_MISSING);
    }

    @Test
    void KDT는_1회만_지원하므로_수강_중이거나_수료했으면_NOT_ELIGIBLE() {
        EligibilityConditionInput kdt = condition(ConditionType.PARTICIPATION_HISTORY, RequirementType.REQUIRED,
                Map.of("status", "ONCE_ONLY", "program", "KDT"), ValidationStatus.VALID);

        assertThat(resultWith(profileBuilder().kdtParticipation(TrainingParticipation.NONE).build(), kdt)).isEqualTo(MATCHED);
        assertThat(evaluate(profileBuilder().kdtParticipation(TrainingParticipation.COMPLETED).build(), List.of(kdt), List.of())
                .status()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
        assertThat(resultWith(profileBuilder().kdtParticipation(TrainingParticipation.IN_PROGRESS).build(), kdt))
                .isEqualTo(NOT_MATCHED);
        assertThat(resultWith(profileBuilder().kdtParticipation(TrainingParticipation.DROPPED_OUT).build(), kdt))
                .isEqualTo(CHECK_REQUIRED);
        assertThat(resultWith(profileBuilder().kdtParticipation(TrainingParticipation.UNKNOWN).build(), kdt))
                .isEqualTo(USER_INFO_MISSING);
        assertThat(resultWith(profile(), kdt)).isEqualTo(USER_INFO_MISSING);
    }

    @Test
    void 알_수_없는_형식의_훈련_조건은_DATA_UNCLEAR() {
        assertThat(result(condition(ConditionType.TRAINING_CARD, RequirementType.REQUIRED,
                Map.of("status", "SOMETHING"), ValidationStatus.VALID))).isEqualTo(DATA_UNCLEAR);
        assertThat(result(condition(ConditionType.PARTICIPATION_HISTORY, RequirementType.REQUIRED,
                Map.of("status", "ONCE_ONLY", "program", "OTHER"), ValidationStatus.VALID))).isEqualTo(DATA_UNCLEAR);
    }

    // ── 보조 ──

    private ConditionMatchResult resultWith(EligibilityProfile p, EligibilityConditionInput condition) {
        return evaluate(p, List.of(condition), List.of()).conditions().get(0).result();
    }

    private EligibilityEvaluation evaluate(EligibilityProfile p, List<EligibilityConditionInput> conditions, List<String> regionCodes) {
        return engine.evaluate(EligibilityProfileInput.from(p), conditions, regionCodes, index, TODAY);
    }

    private ConditionMatchResult result(EligibilityConditionInput condition) {
        return evaluate(profile(), List.of(condition), List.of()).conditions().get(0).result();
    }

    private EligibilityProfile profile() {
        return profileBuilder().build();
    }

    /** 1998-05-15생, 강남구 거주, 미취업, 대학 졸업 */
    private EligibilityProfile.EligibilityProfileBuilder profileBuilder() {
        return EligibilityProfile.builder()
                .userId(1L)
                .birthDate(LocalDate.of(1998, 5, 15))
                .residenceRegion(gangnam)
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY)
                .enrollmentStatus(EnrollmentStatus.GRADUATED);
    }

    private static EligibilityConditionInput age(int min, int max) {
        return condition(ConditionType.AGE, RequirementType.REQUIRED,
                Map.of("status", "RANGE", "min", min, "max", max), ValidationStatus.VALID);
    }

    private static EligibilityConditionInput unverifiedAge(int min, int max) {
        return condition(ConditionType.AGE, RequirementType.REQUIRED,
                Map.of("status", "RANGE", "min", min, "max", max), ValidationStatus.UNVERIFIED);
    }

    private static EligibilityConditionInput region(String status) {
        return condition(ConditionType.REGION, RequirementType.REQUIRED,
                Map.of("status", status, "role", "RESIDENCE"), ValidationStatus.VALID);
    }

    private static EligibilityConditionInput code(ConditionType type, String... values) {
        return condition(type, RequirementType.REQUIRED,
                Map.of("status", "SPECIFIC", "match", "ANY_OF", "values", List.of(values)), ValidationStatus.VALID);
    }

    private static EligibilityConditionInput income(String type, Integer min, Integer max, Boolean maxInclusive,
                                               String subject, ValidationStatus validation) {
        Map<String, Object> e = new HashMap<>();
        e.put("status", min != null && max != null ? "RANGE" : min != null ? "MIN_ONLY" : "MAX_ONLY");
        e.put("type", type);
        if (min != null) e.put("min", min);
        if (max != null) e.put("max", max);
        if (maxInclusive != null) e.put("maxInclusive", maxInclusive);
        if (subject != null) e.put("subject", subject);
        e.put("unitConfirmed", true);
        return condition(ConditionType.INCOME, RequirementType.REQUIRED, e, validation);
    }

    private static EligibilityConditionInput amount(int max, boolean maxInclusive, String subject, String period) {
        Map<String, Object> e = new HashMap<>(Map.of("status", "MAX_ONLY", "type", "AMOUNT", "max", max,
                "maxInclusive", maxInclusive, "subject", subject, "period", period, "unitConfirmed", true));
        return condition(ConditionType.INCOME, RequirementType.REQUIRED, e, ValidationStatus.VALID);
    }

    private static EligibilityConditionInput condition(ConditionType type, RequirementType requirement,
                                                  Map<String, Object> expression, ValidationStatus validation) {
        return new EligibilityConditionInput(type, requirement, type.name().toLowerCase(), expression, validation);
    }
}
