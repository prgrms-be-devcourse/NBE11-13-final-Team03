package com.team3.dataservice.opportunity.normalization;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 온통청년 코드값 → 내부 값 매핑 (youth-policy-codes.yaml).
 * 공식 코드정의서로 확인한 코드만 넣는다. 표에 없는 코드는 정규화에서 NEEDS_REVIEW로 남는다.
 *
 * 내부 값 규칙: 제한 없음은 NO_LIMIT, 기타(본문 확인 필요)는 OTHER, 그 외는 각 필드의 내부 값 (예: jobCd → EmploymentStatus 이름)
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "youth-policy.codes")
public class YouthPolicyCodeTable {

    public static final String NO_LIMIT = "NO_LIMIT";
    public static final String OTHER = "OTHER";

    /** 필드명(mrgSttsCd 등) → (코드 → 내부 값) */
    private Map<String, Map<String, String>> fields = new HashMap<>();

    public Optional<String> value(String field, String code) {
        return Optional.ofNullable(codes(field).get(code));
    }

    public boolean configured(String field) {
        return !codes(field).isEmpty();
    }

    /** 필드명은 대소문자를 구분하지 않는다 (sBizCd / sbizCd) */
    private Map<String, String> codes(String field) {
        return fields.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(field))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(Map.of());
    }
}
