package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 코드 필드(mrgSttsCd, jobCd, schoolCd, plcyMajorCd, sBizCd, earnCndSeCd) 규칙 기반 정규화.
 *
 * - 여러 코드가 쉼표·공백으로 함께 오면 나눠서 코드표로 매핑한다 ("이 중 하나" 의미).
 * - 빈 값은 UNKNOWN (제한 없음으로 해석하지 않는다).
 * - 코드표에 없는 코드는 버리지 않고 기록하며 NEEDS_REVIEW. 코드표가 비어 있으면 모든 코드가 여기에 해당한다.
 * - "제한 없음"과 구체적인 값이 함께 오면 모순이므로 NEEDS_REVIEW.
 * - "기타"는 본문 확인이 필요하므로 NEEDS_REVIEW.
 */
@Component
@RequiredArgsConstructor
public class CodeConditionNormalizer {

    private final YouthPolicyCodeTable codeTable;

    public CodeNormalization normalize(String field, String rawValue) {
        String raw = rawValue == null || rawValue.isBlank() ? null : rawValue.trim();
        if (raw == null) {
            return new CodeNormalization(field, CodeConditionStatus.UNKNOWN, List.of(), List.of(),
                    ValidationStatus.UNKNOWN, "값 없음", null);
        }

        Set<String> values = new TreeSet<>();
        Set<String> unmapped = new TreeSet<>();
        for (String code : raw.split("[,\\s|;]+")) {
            if (code.isEmpty()) {
                continue;
            }
            codeTable.value(field, code).ifPresentOrElse(values::add, () -> unmapped.add(code));
        }

        if (values.isEmpty()) {
            String reason = codeTable.configured(field) ? "코드표에 없는 코드" : "코드표 미설정 (코드정의서 확인 필요)";
            return new CodeNormalization(field, CodeConditionStatus.UNKNOWN, List.of(), List.copyOf(unmapped),
                    ValidationStatus.NEEDS_REVIEW, reason, raw);
        }

        boolean noLimit = values.contains(YouthPolicyCodeTable.NO_LIMIT);
        if (noLimit && values.size() > 1) {
            return new CodeNormalization(field, CodeConditionStatus.UNKNOWN, List.copyOf(values), List.copyOf(unmapped),
                    ValidationStatus.NEEDS_REVIEW, "제한 없음과 구체적인 값이 함께 있음", raw);
        }
        CodeConditionStatus status = noLimit ? CodeConditionStatus.NO_LIMIT : CodeConditionStatus.SPECIFIC;

        String reason = null;
        ValidationStatus validation = ValidationStatus.VALID;
        if (!unmapped.isEmpty()) {
            validation = ValidationStatus.NEEDS_REVIEW;
            reason = "코드표에 없는 코드가 있음";
        } else if (values.contains(YouthPolicyCodeTable.OTHER)) {
            validation = ValidationStatus.NEEDS_REVIEW;
            reason = "기타 코드 — 본문 확인 필요";
        }
        return new CodeNormalization(field, status, noLimit ? List.of() : List.copyOf(values), List.copyOf(unmapped),
                validation, reason, raw);
    }
}
