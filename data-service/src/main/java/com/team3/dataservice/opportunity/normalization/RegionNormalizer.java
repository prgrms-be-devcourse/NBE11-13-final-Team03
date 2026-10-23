package com.team3.dataservice.opportunity.normalization;

import com.team3.dataservice.opportunity.domain.entity.ValidationStatus;
import com.team3.dataservice.region.directory.RegionIndex;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * zipCd(정책거주지역코드) 규칙 기반 정규화.
 *
 * - 쉼표·공백 등으로 나뉜 코드를 하나씩 읽는다. 5자리(시·군·구)는 뒤에 00000을 붙이고, 10자리는 그대로 사전에서 찾는다.
 * - 시와 그 하위 구가 함께 있으면 시만 남기고, 어떤 지역의 하위가 모두 있으면 그 상위로 묶는다.
 * - 모든 시·도가 포함되면 NATIONAL. 빈 값은 UNKNOWN (전국으로 해석하지 않는다).
 * - 사전에 없는 코드(통합 전 옛 코드 등)는 버리지 않고 기록하며 NEEDS_REVIEW로 둔다.
 */
@Component
public class RegionNormalizer {

    public RegionNormalization normalize(String zipCd, RegionIndex index) {
        String raw = zipCd == null || zipCd.isBlank() ? null : zipCd.trim();
        if (raw == null) {
            return new RegionNormalization(RegionStatus.UNKNOWN, List.of(), List.of(), ValidationStatus.UNKNOWN,
                    "지역 코드 없음", null);
        }

        Set<String> mapped = new TreeSet<>();
        Set<String> unmapped = new TreeSet<>();
        for (String token : raw.split("[^0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String code = toRegionCode(token);
            if (code != null && index.contains(code)) {
                mapped.add(code);
            } else {
                unmapped.add(token);
            }
        }

        if (mapped.isEmpty()) {
            return new RegionNormalization(RegionStatus.UNKNOWN, List.of(), List.copyOf(unmapped),
                    ValidationStatus.NEEDS_REVIEW, "지역 사전에서 찾을 수 있는 코드가 없음", raw);
        }

        Set<String> compressed = compress(mapped, index);
        ValidationStatus validation = unmapped.isEmpty() ? ValidationStatus.VALID : ValidationStatus.NEEDS_REVIEW;
        String reason = unmapped.isEmpty() ? null : "지역 사전에 없는 코드가 있음 (통합 전 옛 코드 가능성)";

        if (compressed.containsAll(index.topLevel())) {
            return new RegionNormalization(RegionStatus.NATIONAL, List.of(), List.copyOf(unmapped), validation,
                    reason == null ? "모든 시·도 포함" : reason, raw);
        }
        return new RegionNormalization(RegionStatus.SPECIFIC, List.copyOf(compressed), List.copyOf(unmapped),
                validation, reason, raw);
    }

    /** 5자리 → 뒤에 00000, 10자리 → 그대로, 2자리(시·도) → 뒤에 00000000 */
    static String toRegionCode(String token) {
        return switch (token.length()) {
            case 2 -> token + "00000000";
            case 5 -> token + "00000";
            case 10 -> token;
            default -> null;
        };
    }

    /** 상위가 이미 있는 하위 코드를 지우고, 하위가 모두 있는 지역은 상위로 묶는다 (더 바뀌지 않을 때까지) */
    static Set<String> compress(Set<String> codes, RegionIndex index) {
        Set<String> result = new TreeSet<>(codes);
        result.removeIf(code -> index.ancestorsAndSelf(code).stream().skip(1).anyMatch(codes::contains));

        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> parents = new TreeSet<>();
            for (String code : result) {
                index.find(code).map(RegionIndex.Entry::parentCode).filter(index::contains).ifPresent(parents::add);
            }
            for (String parent : parents) {
                Set<String> children = index.children(parent);
                if (!children.isEmpty() && result.containsAll(children)) {
                    result.removeAll(children);
                    result.add(parent);
                    changed = true;
                }
            }
        }
        return result;
    }
}
