package com.team3.backend.opportunity.support;

import com.team3.backend.opportunity.domain.entity.OpportunityCondition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 테스트용 조건 묶음 키. data-service의 ConditionSetKey와 같은 규칙이다 (메인 서비스에는 묶음을 만드는 코드가 없다).
 * 판정 입력(조건 유형·요건 유형·조건 필드·expression·검증 상태 + 거주지역 코드)이 같으면 같은 키를 낸다.
 */
final class ConditionSetKeys {

    private static final String VERSION = "1";

    private ConditionSetKeys() {
    }

    static String hash(List<OpportunityCondition> conditions, Collection<String> residenceRegionCodes) {
        List<String> lines = new ArrayList<>();
        for (OpportunityCondition c : conditions) {
            lines.add(name(c.getConditionType()) + "|" + name(c.getRequirementType()) + "|"
                    + (c.getConditionField() == null ? "" : c.getConditionField()) + "|" + name(c.getValidationStatus())
                    + "|" + canonicalValue(c.getExpression()));
        }
        Collections.sort(lines);
        List<String> codes = new ArrayList<>(residenceRegionCodes);
        Collections.sort(codes);
        String canonical = "v" + VERSION + "\n" + String.join("\n", lines) + "\nresidence=" + String.join(",", codes);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            StringJoiner joiner = new StringJoiner(",", "{", "}");
            sorted.forEach((k, v) -> joiner.add(quote(k) + ":" + canonicalValue(v)));
            return joiner.toString();
        }
        if (value instanceof Collection<?> list) {
            StringJoiner joiner = new StringJoiner(",", "[", "]");
            list.forEach(v -> joiner.add(canonicalValue(v)));
            return joiner.toString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Enum<?> e) {
            return quote(e.name());
        }
        return quote(value.toString());
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
