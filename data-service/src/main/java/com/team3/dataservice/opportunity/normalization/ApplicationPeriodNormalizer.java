package com.team3.dataservice.opportunity.normalization;

import com.team3.dataservice.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.dataservice.opportunity.domain.status.ApplicationPeriod;
import com.team3.dataservice.opportunity.domain.entity.PeriodType;
import com.team3.dataservice.opportunity.domain.entity.ValidationStatus;
import com.team3.dataservice.opportunity.normalization.dto.YouthPolicyRaw;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 신청기간 규칙 기반 정규화 (aplyPrdSeCd, aplyYmd).
 * 사업기간(bizPrdBgngYmd, bizPrdEndYmd)은 신청기간으로 쓰지 않는다.
 * 기준일과 비교한 현재 신청 상태는 여기서 계산하지 않는다 → {@link ApplicationStatusCalculator}
 *
 * 0057001 고정 기간: aplyYmd에서 "시작 ~ 종료"를 모두 찾아 기간별로 나눈다 (복수 기간 지원).
 * 0057002 상시: ALWAYS, 0057003 마감: CLOSED, 코드 없음: UNKNOWN.
 */
@Component
public class ApplicationPeriodNormalizer {

    static final String FIXED_CODE = "0057001";
    static final String ALWAYS_CODE = "0057002";
    static final String CLOSED_CODE = "0057003";

    /** 20260101, 2026-01-01, 2026.01.01, 2026/1/1, 2026. 1. 1 */
    private static final String DATE = "(\\d{8}|\\d{4}\\s*[.\\-/]\\s*\\d{1,2}\\s*[.\\-/]\\s*\\d{1,2})";
    /** 날짜 뒤 마침표·요일 표기 허용: "2026.10.01.(수) ~ 2026.10.31.(금)" */
    private static final String SUFFIX = "\\.?\\s*(?:\\([^)]{1,3}\\))?";
    private static final Pattern RANGE = Pattern.compile(DATE + "?" + SUFFIX + "\\s*[~∼～]\\s*" + DATE + "?");

    public List<ApplicationPeriod> normalize(YouthPolicyRaw raw) {
        String code = raw.aplyPrdSeCd() == null ? "" : raw.aplyPrdSeCd().trim();
        String text = blankToNull(raw.aplyYmd());

        return switch (code) {
            case ALWAYS_CODE -> List.of(new ApplicationPeriod(PeriodType.ALWAYS, null, null, ValidationStatus.VALID, text));
            case CLOSED_CODE -> List.of(new ApplicationPeriod(PeriodType.CLOSED, null, null, ValidationStatus.VALID, text));
            case FIXED_CODE -> parseFixed(text);
            case "" -> List.of(new ApplicationPeriod(PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN, text));
            default -> List.of(new ApplicationPeriod(PeriodType.UNKNOWN, null, null, ValidationStatus.NEEDS_REVIEW, text));
        };
    }

    private List<ApplicationPeriod> parseFixed(String text) {
        if (text == null) {
            return List.of(new ApplicationPeriod(PeriodType.FIXED, null, null, ValidationStatus.UNKNOWN, null));
        }
        List<ApplicationPeriod> periods = new ArrayList<>(parseRanges(text));
        if (periods.isEmpty()) {
            // 고정 기간 코드인데 날짜를 찾지 못함 → 임의로 채우지 않고 오류로 남긴다
            periods.add(new ApplicationPeriod(PeriodType.FIXED, null, null, ValidationStatus.INVALID, text));
        }
        return List.copyOf(periods);
    }

    /** 텍스트에서 "시작 ~ 종료" 형태를 모두 찾아 FIXED 기간으로 반환한다. 없으면 빈 목록. */
    public static List<ApplicationPeriod> parseRanges(String text) {
        List<ApplicationPeriod> periods = new ArrayList<>();
        Matcher matcher = RANGE.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1) == null && matcher.group(2) == null) {
                continue;
            }
            periods.add(toPeriod(matcher.group(1), matcher.group(2), matcher.group().trim()));
        }
        return periods;
    }

    private static ApplicationPeriod toPeriod(String startText, String endText, String periodText) {
        try {
            LocalDate start = parseDate(startText);
            LocalDate end = parseDate(endText);
            ValidationStatus validation = start != null && end != null && start.isAfter(end)
                    ? ValidationStatus.INVALID
                    : ValidationStatus.VALID;
            return new ApplicationPeriod(PeriodType.FIXED, start, end, validation, periodText);
        } catch (DateTimeException e) {
            // 20260231처럼 존재하지 않는 날짜
            return new ApplicationPeriod(PeriodType.FIXED, null, null, ValidationStatus.INVALID, periodText);
        }
    }

    static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\s", "");
        if (digits.matches("\\d{8}")) {
            return LocalDate.of(Integer.parseInt(digits.substring(0, 4)),
                    Integer.parseInt(digits.substring(4, 6)), Integer.parseInt(digits.substring(6, 8)));
        }
        String[] parts = digits.split("[.\\-/]");
        return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
