package com.team3.backend.eligibility.engine;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.RequirementType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.region.directory.RegionIndex;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;

import static com.team3.backend.eligibility.domain.entity.ConditionMatchResult.*;

/**
 * 신청 가능성 판정 엔진. 정규화된 조건과 판정 프로필을 비교한다. DB·외부 호출 없이 순수 계산만 한다.
 *
 * 조건별 공통 규칙
 * - 우대조건(PREFERRED)은 판정하지 않는다.
 * - 정책 조건 원문(UNSTRUCTURED)은 사용자 확인 필요(CHECK_REQUIRED).
 * - 정책 데이터에 조건이 비어 있으면 NOT_SPECIFIED (집계 제외). 단 연령·지역이 비어 있으면 ELIGIBLE을 확정하지 않도록 CHECK_REQUIRED.
 * - 조건 검증 상태가 INVALID·DATA_CONFLICT면 DATA_UNCLEAR. NEEDS_REVIEW 조건은 사용자 값과 일치하면 MATCHED, 아니면 DATA_UNCLEAR.
 * - 사용자 값이 없거나 "모름"이면 USER_INFO_MISSING.
 * 기준일(today)은 한국 시간 날짜로 넘긴다. 만 나이는 기준일로 계산한다.
 */
@Component
public class EligibilityEngine {

    private static final String UNSTRUCTURED = "UNSTRUCTURED";

    public EligibilityEvaluation evaluate(EligibilityProfile profile, List<EligibilityConditionInput> conditions,
                                          Collection<String> residenceRegionCodes, RegionIndex regionIndex, LocalDate today) {
        List<ConditionEvaluation> results = new ArrayList<>();
        for (EligibilityConditionInput c : conditions) {
            if (c.requirement() == RequirementType.PREFERRED) {
                continue;
            }
            results.add(applyValidation(c, evaluate(c, profile, residenceRegionCodes, regionIndex, today)));
        }
        return aggregate(results);
    }

    // ── 집계 ──

    static EligibilityEvaluation aggregate(List<ConditionEvaluation> results) {
        boolean notEligible = false, unclear = false, possibly = false;
        for (ConditionEvaluation r : results) {
            boolean exclusion = r.requirement() == RequirementType.EXCLUSION;
            switch (r.result()) {
                case NOT_MATCHED -> notEligible |= !exclusion;
                case MATCHED -> notEligible |= exclusion;
                case DATA_UNCLEAR -> unclear = true;
                case USER_INFO_MISSING, CHECK_REQUIRED -> possibly = true;
                case NOT_SPECIFIED -> {
                }
            }
        }
        if (notEligible) {
            return new EligibilityEvaluation(EligibilityStatus.NOT_ELIGIBLE, results, "충족하지 않는 필수조건이 있거나 제외 대상에 해당합니다.");
        }
        if (unclear) {
            return new EligibilityEvaluation(EligibilityStatus.UNKNOWN, results, "정책 조건 정보가 불확실해 판정할 수 없는 조건이 있습니다.");
        }
        if (possibly) {
            return new EligibilityEvaluation(EligibilityStatus.POSSIBLY_ELIGIBLE, results,
                    "확인된 조건은 충족하지만 추가 입력이나 직접 확인이 필요한 조건이 있습니다.");
        }
        return new EligibilityEvaluation(EligibilityStatus.ELIGIBLE, results, "확인된 필수조건을 모두 충족합니다.");
    }

    /** 조건 검증 상태 반영 */
    private static ConditionEvaluation applyValidation(EligibilityConditionInput c, ConditionEvaluation e) {
        ValidationStatus v = c.validationStatus();
        if (e.result() == NOT_SPECIFIED || e.result() == CHECK_REQUIRED && UNSTRUCTURED.equals(status(c))) {
            return e;
        }
        if (v == ValidationStatus.INVALID || v == ValidationStatus.DATA_CONFLICT) {
            return with(e, DATA_UNCLEAR, "정책 조건에 오류·모순이 있어 판정할 수 없습니다.");
        }
        if (v == ValidationStatus.NEEDS_REVIEW && e.result() != MATCHED && e.result() != CHECK_REQUIRED) {
            return with(e, DATA_UNCLEAR, "정책 조건이 검토 중이어서 판정할 수 없습니다.");
        }
        return e;
    }

    // ── 조건별 판정 ──

    private ConditionEvaluation evaluate(EligibilityConditionInput c, EligibilityProfile p, Collection<String> regionCodes,
                                         RegionIndex index, LocalDate today) {
        String status = status(c);
        if (UNSTRUCTURED.equals(status)) {
            return result(c, "TEXT", null, null, CHECK_REQUIRED,
                    c.requirement() == RequirementType.EXCLUSION
                            ? "참여 제한 대상에 해당하지 않는지 공고 원문을 확인해야 합니다."
                            : "추가 신청 자격을 공고 원문에서 확인해야 합니다.");
        }
        if ("UNKNOWN".equals(status) && c.validationStatus() == ValidationStatus.UNKNOWN) {
            boolean core = c.type() == ConditionType.AGE || c.type() == ConditionType.REGION;
            return result(c, null, null, null, core ? CHECK_REQUIRED : NOT_SPECIFIED,
                    core ? "정책 데이터에 이 조건 정보가 없어 직접 확인해야 합니다." : "정책 데이터에 이 조건 정보가 없습니다.");
        }
        if ("UNKNOWN".equals(status)) {
            return result(c, null, null, null, DATA_UNCLEAR, "정책 조건을 확인할 수 없습니다.");
        }
        return switch (c.type()) {
            case AGE -> age(c, p, today);
            case REGION -> region(c, p, regionCodes, index);
            case INCOME -> income(c, p);
            case MARITAL, EMPLOYMENT, EDUCATION, SPECIALIZATION, SPECIAL_TARGET -> code(c, p);
            default -> result(c, null, null, null, DATA_UNCLEAR, "판정 규칙이 없는 조건입니다.");
        };
    }

    private ConditionEvaluation age(EligibilityConditionInput c, EligibilityProfile p, LocalDate today) {
        Map<String, Object> e = c.expression();
        String status = status(c);
        if ("NO_LIMIT".equals(status) || "NOT_APPLICABLE".equals(status)) {
            return result(c, "ANY", status, null, MATCHED, "연령 제한이 없습니다.");
        }
        if (p.getBirthDate() == null) {
            return result(c, null, e, null, USER_INFO_MISSING, "생년월일 정보가 필요합니다.");
        }
        if ("DATE_BOUND".equals(status)) {
            LocalDate after = date(e.get("bornOnOrAfter"));
            LocalDate before = date(e.get("bornOnOrBefore"));
            boolean ok = (after == null || !p.getBirthDate().isBefore(after)) && (before == null || !p.getBirthDate().isAfter(before));
            return result(c, "BORN_BETWEEN", Map.of("bornOnOrAfter", String.valueOf(after), "bornOnOrBefore", String.valueOf(before)),
                    p.getBirthDate().toString(), ok ? MATCHED : NOT_MATCHED, ok ? "출생일 조건을 충족합니다." : "출생일 조건을 충족하지 않습니다.");
        }
        int age = Period.between(p.getBirthDate(), today).getYears();
        Integer min = integer(e.get("min"));
        Integer max = integer(e.get("max"));
        boolean ok = (min == null || age >= min) && (max == null || age <= max);
        String operator = min != null && max != null ? "BETWEEN" : min != null ? "GTE" : "LTE";
        Map<String, Object> expected = new LinkedHashMap<>();
        if (min != null) expected.put("min", min);
        if (max != null) expected.put("max", max);
        return result(c, operator, expected, age, ok ? MATCHED : NOT_MATCHED,
                ok ? "연령 조건을 충족합니다." : "연령 조건을 충족하지 않습니다.");
    }

    private ConditionEvaluation region(EligibilityConditionInput c, EligibilityProfile p, Collection<String> codes, RegionIndex index) {
        if ("NATIONAL".equals(status(c))) {
            return result(c, "ANY", "NATIONAL", null, MATCHED, "전국 대상입니다.");
        }
        String userCode = p.getResidenceRegion() == null ? null : p.getResidenceRegion().getRegionCode();
        if (userCode == null) {
            return result(c, "IN", codes, null, USER_INFO_MISSING, "거주지역 정보가 필요합니다.");
        }
        List<String> chain = index.contains(userCode) ? index.ancestorsAndSelf(userCode) : List.of(userCode);
        boolean ok = chain.stream().anyMatch(codes::contains);
        return result(c, "IN", List.copyOf(codes), userCode, ok ? MATCHED : NOT_MATCHED,
                ok ? "거주지역 조건을 충족합니다." : "거주지역 조건을 충족하지 않습니다.");
    }

    private ConditionEvaluation code(EligibilityConditionInput c, EligibilityProfile p) {
        if ("NO_LIMIT".equals(status(c))) {
            return result(c, "ANY", "NO_LIMIT", null, MATCHED, "제한이 없습니다.");
        }
        List<String> values = strings(c.expression().get("values"));
        Optional<UserValues> user = userValues(c.type(), p);
        if (user.isEmpty()) {
            return result(c, "ANY_OF", values, null, USER_INFO_MISSING, label(c.type()) + " 정보가 필요합니다.");
        }
        Set<String> matched = new TreeSet<>(user.get().values());
        matched.retainAll(values);
        matched.remove(com.team3.backend.opportunity.normalization.YouthPolicyCodeTable.OTHER);
        Object actual = user.get().values().size() == 1 ? user.get().values().iterator().next() : user.get().values();
        if (!matched.isEmpty()) {
            if (user.get().onLeave()) {
                return result(c, "ANY_OF", values, actual, CHECK_REQUIRED, "휴학 중에도 재학 조건이 인정되는지 확인해야 합니다.");
            }
            return result(c, "ANY_OF", values, actual, MATCHED, label(c.type()) + " 조건을 충족합니다.");
        }
        if (values.contains(com.team3.backend.opportunity.normalization.YouthPolicyCodeTable.OTHER)) {
            return result(c, "ANY_OF", values, actual, CHECK_REQUIRED, label(c.type()) + " 기타 조건을 공고 원문에서 확인해야 합니다.");
        }
        return result(c, "ANY_OF", values, actual, NOT_MATCHED, label(c.type()) + " 조건을 충족하지 않습니다.");
    }

    private ConditionEvaluation income(EligibilityConditionInput c, EligibilityProfile p) {
        Map<String, Object> e = c.expression();
        String status = status(c);
        if ("NO_LIMIT".equals(status)) {
            return result(c, "ANY", "NO_LIMIT", null, MATCHED, "소득 제한이 없습니다.");
        }
        if (!Set.of("RANGE", "MIN_ONLY", "MAX_ONLY").contains(status)) {
            return result(c, null, e, null, DATA_UNCLEAR, "소득 기준이 복합적이거나 불명확합니다.");
        }
        String type = String.valueOf(e.get("type"));
        BigDecimal actual;
        switch (type) {
            case "MEDIAN_PERCENT" -> {
                if (p.getMedianIncomeStatus() != AnswerStatus.PROVIDED || p.getMedianIncomePercent() == null) {
                    return result(c, "RANGE", e, null, USER_INFO_MISSING, "기준중위소득 대비 비율 정보가 필요합니다.");
                }
                actual = p.getMedianIncomePercent();
            }
            case "STUDENT_LOAN_BRACKET" -> {
                if (p.getStudentLoanBracketStatus() != AnswerStatus.PROVIDED || p.getStudentLoanBracket() == null) {
                    return result(c, "RANGE", e, null, USER_INFO_MISSING, "학자금 지원구간 정보가 필요합니다.");
                }
                actual = BigDecimal.valueOf(p.getStudentLoanBracket());
            }
            case "AMOUNT" -> {
                if (!Boolean.TRUE.equals(e.get("unitConfirmed"))) {
                    return result(c, "RANGE", e, null, DATA_UNCLEAR, "소득 금액의 단위가 확인되지 않았습니다.");
                }
                String subject = String.valueOf(e.get("subject"));
                IncomeAnswer answer = "HOUSEHOLD".equals(subject) ? p.getHouseholdIncome()
                        : "INDIVIDUAL".equals(subject) ? p.getPersonalIncome() : null;
                if (answer == null && !"HOUSEHOLD".equals(subject) && !"INDIVIDUAL".equals(subject)) {
                    return result(c, "RANGE", e, null, CHECK_REQUIRED, "개인 소득 기준인지 가구 소득 기준인지 공고에서 확인해야 합니다.");
                }
                if (answer == null || answer.getStatus() != AnswerStatus.PROVIDED) {
                    return result(c, "RANGE", e, null, USER_INFO_MISSING,
                            ("HOUSEHOLD".equals(subject) ? "가구" : "개인") + " 소득 정보가 필요합니다.");
                }
                String period = String.valueOf(e.get("period"));
                if (!"MONTHLY".equals(period) && !"YEARLY".equals(period)) {
                    return result(c, "RANGE", e, null, CHECK_REQUIRED, "월 소득 기준인지 연 소득 기준인지 공고에서 확인해야 합니다.");
                }
                actual = convert(answer.getAmount(), answer.getPeriod(), period);
            }
            default -> {
                return result(c, null, e, null, DATA_UNCLEAR, "판정할 수 없는 소득 기준입니다.");
            }
        }
        boolean ok = within(actual, decimal(e.get("min")), decimal(e.get("max")),
                !Boolean.FALSE.equals(e.get("minInclusive")), !Boolean.FALSE.equals(e.get("maxInclusive")));
        return result(c, "RANGE", e, actual, ok ? MATCHED : NOT_MATCHED, ok ? "소득 조건을 충족합니다." : "소득 조건을 충족하지 않습니다.");
    }

    // ── 사용자 값 ──

    record UserValues(Set<String> values, boolean onLeave) {
    }

    static Optional<UserValues> userValues(ConditionType type, EligibilityProfile p) {
        return switch (type) {
            case MARITAL -> p.getMaritalStatus() == null || p.getMaritalStatus() == MaritalStatus.UNKNOWN
                    ? Optional.empty() : Optional.of(new UserValues(Set.of(p.getMaritalStatus().name()), false));
            case EMPLOYMENT -> p.getEmploymentStatus() == null
                    ? Optional.empty() : Optional.of(new UserValues(Set.of(p.getEmploymentStatus().name()), false));
            case SPECIALIZATION -> p.getMajorField() == null
                    ? Optional.empty() : Optional.of(new UserValues(Set.of(p.getMajorField().name()), false));
            case SPECIAL_TARGET -> specialTargets(p);
            case EDUCATION -> education(p.getEducationLevel(), p.getEnrollmentStatus());
            default -> Optional.empty();
        };
    }

    private static Optional<UserValues> specialTargets(EligibilityProfile p) {
        if (p.getSpecialTargetStatus() == AnswerStatus.NOT_APPLICABLE) {
            return Optional.of(new UserValues(Set.of(), false));
        }
        if (p.getSpecialTargetStatus() != AnswerStatus.PROVIDED) {
            return Optional.empty();
        }
        Set<String> names = new TreeSet<>();
        p.getSpecialTargets().forEach(t -> names.add(t.name()));
        return Optional.of(new UserValues(names, false));
    }

    /** 최종 학력 + 학적 상태 → 정책학력요건코드 내부 값 */
    static Optional<UserValues> education(EducationLevel level, EnrollmentStatus enrollment) {
        if (level == null) {
            return Optional.empty();
        }
        if (level == EducationLevel.MIDDLE_SCHOOL_OR_BELOW) {
            return Optional.of(new UserValues(Set.of("BELOW_HIGH_SCHOOL"), false));
        }
        if (level == EducationLevel.GRADUATE_SCHOOL) {
            return Optional.of(new UserValues(Set.of("GRADUATE_SCHOOL"), false));
        }
        if (enrollment == null) {
            return Optional.empty();
        }
        boolean highSchool = level == EducationLevel.HIGH_SCHOOL;
        String prefix = highSchool ? "HIGH_SCHOOL_" : "UNIVERSITY_";
        return Optional.of(switch (enrollment) {
            case ENROLLED -> new UserValues(Set.of(prefix + "ENROLLED"), false);
            case LEAVE_OF_ABSENCE -> new UserValues(Set.of(prefix + "ENROLLED"), true);
            case EXPECTED_GRADUATION -> new UserValues(Set.of(prefix + "EXPECTED_GRADUATION"), false);
            case GRADUATED -> new UserValues(Set.of(prefix + "GRADUATED"), false);
            case DROPPED_OUT -> new UserValues(Set.of(highSchool ? "BELOW_HIGH_SCHOOL" : "HIGH_SCHOOL_GRADUATED"), false);
        });
    }

    // ── 보조 ──

    static boolean within(BigDecimal value, BigDecimal min, BigDecimal max, boolean minInclusive, boolean maxInclusive) {
        if (min != null) {
            int cmp = value.compareTo(min);
            if (cmp < 0 || (cmp == 0 && !minInclusive)) return false;
        }
        if (max != null) {
            int cmp = value.compareTo(max);
            if (cmp > 0 || (cmp == 0 && !maxInclusive)) return false;
        }
        return true;
    }

    /** 사용자 소득을 정책 기준 기간으로 환산 (월 ↔ 연) */
    static BigDecimal convert(BigDecimal amount, IncomePeriod from, String to) {
        if (from == null || from.name().equals(to)) {
            return amount;
        }
        return from == IncomePeriod.MONTHLY
                ? amount.multiply(BigDecimal.valueOf(12))
                : amount.divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP);
    }

    private static String status(EligibilityConditionInput c) {
        Object s = c.expression() == null ? null : c.expression().get("status");
        return s == null ? "UNKNOWN" : s.toString();
    }

    private static ConditionEvaluation result(EligibilityConditionInput c, String operator, Object expected, Object actual,
                                              ConditionMatchResult result, String reason) {
        return new ConditionEvaluation(c.type(), c.field(), c.requirement(),
                operator, expected, actual, result, reason);
    }

    private static ConditionEvaluation with(ConditionEvaluation e, ConditionMatchResult result, String reason) {
        return new ConditionEvaluation(e.type(), e.field(), e.requirement(), e.operator(), e.expected(), e.actual(), result, reason);
    }

    private static String label(ConditionType type) {
        return switch (type) {
            case MARITAL -> "혼인 상태";
            case EMPLOYMENT -> "취업 상태";
            case EDUCATION -> "학력";
            case SPECIALIZATION -> "전공";
            case SPECIAL_TARGET -> "특화 대상";
            default -> "조건";
        };
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(String::valueOf).toList();
    }

    private static Integer integer(Object value) {
        return value == null ? null : Integer.valueOf(value.toString());
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static LocalDate date(Object value) {
        return value == null ? null : LocalDate.parse(value.toString());
    }
}
