package com.team3.dataservice.opportunity.normalization;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 온통청년 대분류(lclsfNm)·중분류(mclsfNm)를 (대분류, 중분류) 짝으로 나눈다.
 * 원본은 여러 분류를 쉼표로 이어 붙이고, 대·중분류 개수가 같으면 같은 위치끼리 짝이다.
 * <ul>
 *   <li>"일자리,교육" + "취업,미래역량강화" → (일자리, 취업), (교육, 미래역량강화)</li>
 *   <li>"일자리,일자리" + "취업,창업" → (일자리, 취업), (일자리, 창업)</li>
 * </ul>
 * 개수가 다르면 짝을 추정하지 않는다. 대분류가 하나뿐이면 모든 중분류와 짝짓고,
 * 그 외에는 대분류만 있는 행과 중분류만 있는 행으로 나눠 어느 쪽으로 찾아도 빠지지 않게 한다.
 * 앞뒤 공백·빈 항목은 버리고, 같은 짝은 처음 나온 위치에 한 번만 남긴다.
 */
public final class CategoryNormalizer {

    public record CategoryPair(String large, String medium) {
    }

    private CategoryNormalizer() {
    }

    public static List<CategoryPair> split(String large, String medium) {
        List<String> larges = tokens(large);
        List<String> mediums = tokens(medium);
        Set<CategoryPair> pairs = new LinkedHashSet<>();
        if (!larges.isEmpty() && larges.size() == mediums.size()) {
            for (int i = 0; i < larges.size(); i++) {
                pairs.add(new CategoryPair(larges.get(i), mediums.get(i)));
            }
        } else if (new LinkedHashSet<>(larges).size() == 1 && !mediums.isEmpty()) {
            mediums.forEach(m -> pairs.add(new CategoryPair(larges.getFirst(), m)));
        } else {
            larges.forEach(l -> pairs.add(new CategoryPair(l, null)));
            mediums.forEach(m -> pairs.add(new CategoryPair(null, m)));
        }
        return List.copyOf(pairs);
    }

    static List<String> tokens(String value) {
        List<String> tokens = new ArrayList<>();
        if (value == null) {
            return tokens;
        }
        for (String token : value.split(",")) {
            String trimmed = token.strip();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }
}
