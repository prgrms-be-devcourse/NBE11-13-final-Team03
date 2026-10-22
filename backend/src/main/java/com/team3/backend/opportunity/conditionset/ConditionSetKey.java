package com.team3.backend.opportunity.conditionset;

import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.dto.ConditionRow;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 조건 묶음 키. 판정 엔진이 기회 버전에서 읽는 입력만으로 만든다 (EligibilityEvaluator 참고).
 * - 자격 조건 행: 조건 유형, 요건 유형, 조건 필드, expression, 검증 상태 (evidence는 판정에 쓰지 않으므로 제외)
 * - 거주지역(RESIDENCE) 코드. 시행 지역(IMPLEMENTATION, 훈련 장소)은 판정에 쓰지 않으므로 제외
 *
 * 정규화 때(메모리의 엔티티)와 백필 때(DB에서 읽은 jsonb)가 같은 키를 내도록 정규 형식으로 바꾼다.
 * - 조건 행과 지역 코드는 정렬한다 (저장 순서와 무관)
 * - expression의 맵 키는 정렬하고, 숫자는 값으로 비교한다 (19, 19L, 19.0은 같음). 목록은 순서를 유지한다
 * 판정 엔진이 읽는 입력이 바뀌면 이 키도 함께 바꿔야 한다.
 *
 * 키 규칙을 바꿀 때는 VERSION을 올린다. 정규 형식 맨 앞에 버전이 들어가므로 새 키는 이전 키와 절대 겹치지 않는다.
 * 기존 버전에 반영하려면 opportunity_versions.condition_set_id를 NULL로 돌리고 백필(ConditionSetBackfill)을 다시 돌린다.
 * 이전 키의 조건 묶음과 그 판정 결과는 어느 버전도 가리키지 않게 되어 쓰이지 않는다.
 */
public final class ConditionSetKey {

    /** 키 규칙 버전. 정규 형식이나 포함하는 입력이 바뀌면 올린다 */
    static final String VERSION = "1";

    private ConditionSetKey() {
    }

    public static String hash(List<OpportunityCondition> conditions, Collection<String> residenceRegionCodes) {
        List<String> lines = new ArrayList<>(conditions.size());
        for (OpportunityCondition c : conditions) {
            lines.add(line(c.getConditionType(), c.getRequirementType(), c.getConditionField(), c.getExpression(),
                    c.getValidationStatus()));
        }
        return digest(lines, residenceRegionCodes);
    }

    public static String hashRows(List<ConditionRow> conditions, Collection<String> residenceRegionCodes) {
        List<String> lines = new ArrayList<>(conditions.size());
        for (ConditionRow c : conditions) {
            lines.add(line(c.conditionType(), c.requirementType(), c.conditionField(), c.expression(),
                    c.validationStatus()));
        }
        return digest(lines, residenceRegionCodes);
    }

    /** 사람이 읽을 수 있는 정규 형식 (테스트·디버깅용) */
    static String canonical(List<String> lines, Collection<String> residenceRegionCodes) {
        List<String> sortedLines = new ArrayList<>(lines);
        Collections.sort(sortedLines);
        List<String> codes = new ArrayList<>(residenceRegionCodes == null ? List.of() : residenceRegionCodes);
        Collections.sort(codes);
        return "v" + VERSION + "\n" + String.join("\n", sortedLines) + "\nresidence=" + String.join(",", codes);
    }

    private static String line(Enum<?> type, Enum<?> requirement, String field, Map<String, Object> expression,
                               Enum<?> validation) {
        return name(type) + "|" + name(requirement) + "|" + (field == null ? "" : field) + "|" + name(validation)
                + "|" + canonicalValue(expression);
    }

    private static String digest(List<String> lines, Collection<String> residenceRegionCodes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(lines, residenceRegionCodes).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(e.getKey())).append(':').append(canonicalValue(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Collection<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object v : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(canonicalValue(v));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean || value instanceof Enum<?>) {
            return value instanceof Enum<?> e ? quote(e.name()) : value.toString();
        }
        return quote(value.toString());
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
