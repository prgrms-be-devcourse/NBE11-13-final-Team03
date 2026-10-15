package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.normalization.official.OfficialText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 소득 규칙 기반 정규화 (earnCndSeCd, earnMinAmt, earnMaxAmt, earnEtcCn, addAplyQlfcCndCn).
 *
 * 텍스트에서 읽는 표현 (소득 문맥이 있는 줄만):
 * - 금액: "월 소득 250만 원 이하", "연 소득 5,000만 원 미만", "50만 원 초과 250만 원 이하", "1억 원 이하"
 * - 기준중위소득: "기준중위소득 120% 이하"
 * - 학자금 지원구간: "학자금 지원구간 8구간 이하", "1~4구간"
 * 지키는 원칙:
 * - 지원금("최대 500만 원 지원")은 비교 표현(이하·미만 등)이 없으므로 소득 조건으로 읽지 않는다.
 * - 재산·자산 문맥이 있는 줄은 소득으로 읽지 않는다 (소득과 재산은 다른 조건).
 * - 개인/가구, 월/연은 문장에 있을 때만 정한다. 없으면 UNKNOWN.
 * - 서로 다른 소득 기준이 여러 개면 고르지 않고 COMPOSITE + NEEDS_REVIEW.
 * - API 구조화 금액(earnMinAmt/earnMaxAmt)은 단위가 확인되지 않아 NEEDS_REVIEW로 둔다. 0은 미입력으로 본다.
 */
@Component
@RequiredArgsConstructor
public class IncomeNormalizer {

    static final String FIELD = "earnCndSeCd";

    private static final Pattern INCOME_CONTEXT = Pattern.compile("소득|급여|보수|연봉|월급|학자금\\s*지원\\s*구간");
    private static final Pattern PROPERTY_CONTEXT = Pattern.compile("재산|자산|부동산|자동차\\s*가액");
    private static final String CMP = "(이하|미만|이내|초과|이상)";
    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*(억|천만|백만|만)?\\s*(원)?\\s*" + CMP);
    private static final Pattern MEDIAN = Pattern.compile("중위\\s*소득\\s*(?:의\\s*)?(\\d{1,3}(?:\\.\\d+)?)\\s*%\\s*" + CMP);
    private static final Pattern LOAN_RANGE = Pattern.compile("(\\d{1,2})\\s*[~∼～\\-]\\s*(\\d{1,2})\\s*구간");
    private static final Pattern LOAN_MAX = Pattern.compile("(\\d{1,2})\\s*구간\\s*(이하|이내|까지)");
    private static final Pattern MONTHLY = Pattern.compile("월\\s*(평균)?\\s*(소득|급여|보수|임금)|월급|월\\s*\\d");
    private static final Pattern YEARLY = Pattern.compile("연\\s*(간|평균)?\\s*(소득|급여|보수|임금)|연봉|연\\s*\\d|1년");
    private static final Pattern HOUSEHOLD = Pattern.compile("가구|세대");
    private static final Pattern INDIVIDUAL = Pattern.compile("본인|개인|신청자");

    private final CodeConditionNormalizer codeNormalizer;

    public IncomeNormalization normalize(YouthPolicyRaw raw) {
        Map<String, String> rawValues = new LinkedHashMap<>();
        rawValues.put("earnCndSeCd", raw.earnCndSeCd());
        rawValues.put("earnMinAmt", raw.earnMinAmt());
        rawValues.put("earnMaxAmt", raw.earnMaxAmt());
        rawValues.put("earnEtcCn", raw.earnEtcCn());

        CodeNormalization code = codeNormalizer.normalize(FIELD, raw.earnCndSeCd());
        boolean codeNoLimit = code.status() == CodeConditionStatus.NO_LIMIT && code.validationStatus() == ValidationStatus.VALID;

        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(extract("earnEtcCn", raw.earnEtcCn()));
        candidates.addAll(extract("addAplyQlfcCndCn", raw.addAplyQlfcCndCn()));
        candidates = distinct(candidates);

        if (candidates.size() > 1) {
            return result(IncomeStatus.COMPOSITE, IncomeType.OTHER, null, null, null, null, "UNKNOWN", "UNKNOWN", false,
                    ValidationStatus.NEEDS_REVIEW, false, joinLines(candidates),
                    "서로 다른 소득 기준이 여러 개 있음 (가구원 수별·대상별 기준 확인 필요)", rawValues);
        }
        if (candidates.size() == 1) {
            Candidate c = candidates.get(0);
            boolean conflict = codeNoLimit;
            return result(c.status(), c.type(), c.min(), c.max(), c.minInclusive(), c.maxInclusive(), c.period(),
                    c.subject(), c.type() != IncomeType.AMOUNT || c.unitConfirmed(),
                    conflict ? ValidationStatus.DATA_CONFLICT : ValidationStatus.VALID, conflict, c.line(),
                    conflict ? "소득 조건 구분은 제한 없음인데 본문에 소득 기준이 있음" : "API 텍스트의 소득 기준", rawValues);
        }

        BigDecimal min = amount(raw.earnMinAmt());
        BigDecimal max = amount(raw.earnMaxAmt());
        if (min != null || max != null) {
            IncomeStatus status = min != null && max != null ? IncomeStatus.RANGE
                    : min != null ? IncomeStatus.MIN_ONLY : IncomeStatus.MAX_ONLY;
            return result(status, IncomeType.AMOUNT, min, max, null, null,
                    "ANNUAL_INCOME".equals(first(code)) ? "YEARLY" : "UNKNOWN", "UNKNOWN", false,
                    ValidationStatus.NEEDS_REVIEW, codeNoLimit, null,
                    "API 소득 금액의 단위·기준이 확인되지 않음 (원문 확인 필요)", rawValues);
        }
        if (codeNoLimit) {
            return result(IncomeStatus.NO_LIMIT, null, null, null, null, null, null, null, false,
                    ValidationStatus.VALID, false, null, "소득 조건 구분: 제한 없음", rawValues);
        }
        if (code.status() == CodeConditionStatus.UNKNOWN && code.raw() == null && blank(raw.earnEtcCn())) {
            return result(IncomeStatus.UNKNOWN, null, null, null, null, null, null, null, false,
                    ValidationStatus.UNKNOWN, false, null, "소득 정보 없음", rawValues);
        }
        return result(IncomeStatus.UNKNOWN, null, null, null, null, null, null, null, false,
                ValidationStatus.NEEDS_REVIEW, false, blank(raw.earnEtcCn()) ? null : raw.earnEtcCn().trim(),
                code.reason() != null ? "소득 조건을 해석할 수 없음: " + code.reason() : "소득 조건을 해석할 수 없음", rawValues);
    }

    // ── 텍스트 추출 ──

    record Candidate(IncomeStatus status, IncomeType type, BigDecimal min, BigDecimal max, Boolean minInclusive,
                     Boolean maxInclusive, String period, String subject, boolean unitConfirmed, String line) {

        boolean sameRule(Candidate o) {
            return status == o.status && type == o.type && Objects.equals(min, o.min) && Objects.equals(max, o.max)
                    && Objects.equals(minInclusive, o.minInclusive) && Objects.equals(maxInclusive, o.maxInclusive)
                    && Objects.equals(period, o.period) && Objects.equals(subject, o.subject);
        }
    }

    static List<Candidate> extract(String field, String text) {
        List<Candidate> result = new ArrayList<>();
        for (String line : OfficialText.lines(text)) {
            if (!INCOME_CONTEXT.matcher(line).find() || PROPERTY_CONTEXT.matcher(line).find()) {
                continue;
            }
            String tagged = "[" + field + "] " + line;
            String subject = HOUSEHOLD.matcher(line).find() ? "HOUSEHOLD" : INDIVIDUAL.matcher(line).find() ? "INDIVIDUAL" : "UNKNOWN";

            Matcher median = MEDIAN.matcher(line);
            if (median.find()) {
                result.add(bound(IncomeType.MEDIAN_PERCENT, new BigDecimal(median.group(1)), median.group(2), "UNKNOWN",
                        subject, true, tagged));
                continue;
            }
            if (line.contains("학자금")) {
                Matcher range = LOAN_RANGE.matcher(line);
                Matcher max = LOAN_MAX.matcher(line);
                if (range.find()) {
                    result.add(new Candidate(IncomeStatus.RANGE, IncomeType.STUDENT_LOAN_BRACKET,
                            new BigDecimal(range.group(1)), new BigDecimal(range.group(2)), true, true,
                            "UNKNOWN", "HOUSEHOLD", true, tagged));
                } else if (max.find()) {
                    result.add(new Candidate(IncomeStatus.MAX_ONLY, IncomeType.STUDENT_LOAN_BRACKET, null,
                            new BigDecimal(max.group(1)), null, true, "UNKNOWN", "HOUSEHOLD", true, tagged));
                }
                continue;
            }
            String period = MONTHLY.matcher(line).find() ? "MONTHLY" : YEARLY.matcher(line).find() ? "YEARLY" : "UNKNOWN";
            List<Candidate> bounds = new ArrayList<>();
            Matcher m = AMOUNT.matcher(line);
            while (m.find()) {
                boolean hasUnit = m.group(2) != null || m.group(3) != null;
                if (!hasUnit) {
                    continue; // 단위 없는 숫자는 금액으로 보지 않는다
                }
                bounds.add(bound(IncomeType.AMOUNT, toWon(m.group(1), m.group(2)), m.group(4), period, subject, true, tagged));
            }
            result.add(merge(bounds));
        }
        result.removeIf(Objects::isNull);
        return result;
    }

    /** 같은 줄의 하한·상한 한 쌍은 범위로 합친다 ("50만 원 초과 250만 원 이하") */
    private static Candidate merge(List<Candidate> bounds) {
        if (bounds.isEmpty()) {
            return null;
        }
        if (bounds.size() == 2) {
            Candidate a = bounds.get(0), b = bounds.get(1);
            Candidate lower = a.status() == IncomeStatus.MIN_ONLY ? a : b.status() == IncomeStatus.MIN_ONLY ? b : null;
            Candidate upper = a.status() == IncomeStatus.MAX_ONLY ? a : b.status() == IncomeStatus.MAX_ONLY ? b : null;
            if (lower != null && upper != null && lower.min().compareTo(upper.max()) <= 0) {
                return new Candidate(IncomeStatus.RANGE, IncomeType.AMOUNT, lower.min(), upper.max(), lower.minInclusive(),
                        upper.maxInclusive(), a.period(), a.subject(), true, a.line());
            }
        }
        return bounds.size() == 1 ? bounds.get(0)
                : new Candidate(IncomeStatus.COMPOSITE, IncomeType.AMOUNT, null, null, null, null,
                bounds.get(0).period(), bounds.get(0).subject(), true, bounds.get(0).line());
    }

    private static Candidate bound(IncomeType type, BigDecimal value, String cmp, String period, String subject,
                                   boolean unitConfirmed, String line) {
        boolean lower = "초과".equals(cmp) || "이상".equals(cmp);
        boolean inclusive = !"미만".equals(cmp) && !"초과".equals(cmp);
        return lower
                ? new Candidate(IncomeStatus.MIN_ONLY, type, value, null, inclusive, null, period, subject, unitConfirmed, line)
                : new Candidate(IncomeStatus.MAX_ONLY, type, null, value, null, inclusive, period, subject, unitConfirmed, line);
    }

    static BigDecimal toWon(String number, String unit) {
        BigDecimal value = new BigDecimal(number.replace(",", ""));
        BigDecimal multiplier = switch (unit == null ? "" : unit) {
            case "억" -> new BigDecimal("100000000");
            case "천만" -> new BigDecimal("10000000");
            case "백만" -> new BigDecimal("1000000");
            case "만" -> new BigDecimal("10000");
            default -> BigDecimal.ONE;
        };
        return value.multiply(multiplier).stripTrailingZeros();
    }

    // ── 보조 ──

    private static List<Candidate> distinct(List<Candidate> candidates) {
        List<Candidate> result = new ArrayList<>();
        for (Candidate c : candidates) {
            if (result.stream().noneMatch(c::sameRule)) {
                result.add(c);
            }
        }
        return result;
    }

    private static String joinLines(List<Candidate> candidates) {
        return String.join("\n", candidates.stream().map(Candidate::line).distinct().toList());
    }

    private static BigDecimal amount(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(value.trim().replace(",", ""));
            return amount.signum() == 0 ? null : amount;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String first(CodeNormalization code) {
        return code.values().isEmpty() ? null : code.values().get(0);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IncomeNormalization result(IncomeStatus status, IncomeType type, BigDecimal min, BigDecimal max,
                                              Boolean minInclusive, Boolean maxInclusive, String period, String subject,
                                              boolean unitConfirmed, ValidationStatus validation, boolean conflict,
                                              String evidenceText, String reason, Map<String, String> raw) {
        return new IncomeNormalization(status, type, min, max, minInclusive, maxInclusive, period, subject, unitConfirmed,
                validation, conflict, evidenceText, reason, raw);
    }
}
