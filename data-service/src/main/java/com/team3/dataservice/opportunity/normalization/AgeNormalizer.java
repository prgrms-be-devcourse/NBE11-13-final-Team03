package com.team3.dataservice.opportunity.normalization;

import com.team3.dataservice.opportunity.domain.entity.ValidationStatus;
import com.team3.dataservice.opportunity.normalization.dto.YouthPolicyRaw;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 연령 규칙 기반 정규화 (sprtTrgtMinAge, sprtTrgtMaxAge, sprtTrgtAgeLmtYn).
 *
 * - 연령 값 "0"과 빈 값은 "입력되지 않음"으로 본다. 0세 이상·이하로 해석하지 않는다.
 * - 0/0/Y(제한 있음인데 연령 없음)는 NO_LIMIT으로 처리하지 않고 UNKNOWN + DATA_CONFLICT로 둔다.
 *   이후 ptcpPrpTrgtCn·addAplyQlfcCndCn·공식 공고 확인(텍스트·AI 단계)에서 보완한다.
 * - 0/0/N도 Y/N의 공식 의미가 확인되기 전에는 NO_LIMIT으로 확정하지 않는다 (NEEDS_REVIEW).
 *   NO_LIMIT은 원문에 "연령 제한 없음"이 명시된 경우에만 보완 단계에서 만든다.
 * - 필드끼리 모순되면 값을 고르지 않고 UNKNOWN으로 둔다.
 */
@Component
public class AgeNormalizer {

    static final int MAX_REASONABLE_AGE = 120;

    public AgeNormalization normalize(YouthPolicyRaw raw) {
        Map<String, String> rawValues = new LinkedHashMap<>();
        rawValues.put("sprtTrgtMinAge", raw.sprtTrgtMinAge());
        rawValues.put("sprtTrgtMaxAge", raw.sprtTrgtMaxAge());
        rawValues.put("sprtTrgtAgeLmtYn", raw.sprtTrgtAgeLmtYn());

        ParsedAge min = parseAge(raw.sprtTrgtMinAge());
        ParsedAge max = parseAge(raw.sprtTrgtMaxAge());
        String limitYn = normalizeYn(raw.sprtTrgtAgeLmtYn());

        if (min.invalid() || max.invalid()) {
            return unknown(ValidationStatus.INVALID, false, "연령 값을 숫자로 해석할 수 없거나 범위를 벗어남", rawValues);
        }
        if (min.value() != null && max.value() != null && min.value() > max.value()) {
            return unknown(ValidationStatus.INVALID, false, "최소 연령이 최대 연령보다 큼", rawValues);
        }

        boolean hasMin = min.value() != null;
        boolean hasMax = max.value() != null;

        if ("N".equals(limitYn)) {
            if (hasMin || hasMax) {
                return unknown(ValidationStatus.DATA_CONFLICT, true, "연령 제한 없음(N)인데 연령 값이 있음", rawValues);
            }
            // Y/N의 공식 의미를 코드 정의서로 확인하기 전에는 제한 없음으로 확정하지 않는다
            return unknown(ValidationStatus.NEEDS_REVIEW, false,
                    "연령 제한 여부 N, 연령 값 없음 — 공식 정의·명시적 근거 확인 전 자동 확정 보류", rawValues);
        }

        if (hasMin && hasMax) {
            return new AgeNormalization(AgeStatus.RANGE, min.value(), max.value(), ValidationStatus.VALID, false,
                    "최소·최대 연령 모두 존재", rawValues);
        }
        if (hasMin) {
            return new AgeNormalization(AgeStatus.MIN_ONLY, min.value(), null, ValidationStatus.VALID, false,
                    "최소 연령만 존재", rawValues);
        }
        if (hasMax) {
            return new AgeNormalization(AgeStatus.MAX_ONLY, null, max.value(), ValidationStatus.VALID, false,
                    "최대 연령만 존재", rawValues);
        }
        if ("Y".equals(limitYn)) {
            return unknown(ValidationStatus.DATA_CONFLICT, true,
                    "연령 제한 있음(Y)인데 최소·최대 연령이 없음(0/0/Y) — 대상 설명·공식 공고 확인 필요", rawValues);
        }
        return unknown(ValidationStatus.UNKNOWN, false, "연령 정보 없음", rawValues);
    }

    private static AgeNormalization unknown(ValidationStatus validation, boolean conflict, String reason,
                                            Map<String, String> rawValues) {
        return new AgeNormalization(AgeStatus.UNKNOWN, null, null, validation, conflict, reason, rawValues);
    }

    /** 빈 값·"0" → 값 없음, 숫자가 아니거나 범위 밖 → invalid */
    static ParsedAge parseAge(String value) {
        if (value == null || value.isBlank()) {
            return new ParsedAge(null, false);
        }
        try {
            int age = Integer.parseInt(value.trim());
            if (age == 0) {
                return new ParsedAge(null, false);
            }
            if (age < 0 || age > MAX_REASONABLE_AGE) {
                return new ParsedAge(null, true);
            }
            return new ParsedAge(age, false);
        } catch (NumberFormatException e) {
            return new ParsedAge(null, true);
        }
    }

    private static String normalizeYn(String value) {
        if (value == null) {
            return null;
        }
        String yn = value.trim().toUpperCase();
        return "Y".equals(yn) || "N".equals(yn) ? yn : null;
    }

    record ParsedAge(Integer value, boolean invalid) {
    }
}
