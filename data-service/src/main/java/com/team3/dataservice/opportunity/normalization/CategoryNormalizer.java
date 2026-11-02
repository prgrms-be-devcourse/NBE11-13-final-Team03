package com.team3.dataservice.opportunity.normalization;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *
 * 온통청년이 분류 체계를 바꾸면서 옛 이름(교육･직업훈련, 금융･복지･문화, 참여･기반 등)이 남아 있어,
 * 짝을 짓기 전에 현재 이름으로 바꾼다. 가운뎃점 모양(･ · ・ ㆍ) 차이는 같은 이름으로 본다.
 * 직업훈련의 대분류 교육･직업훈련은 이 클래스를 거치지 않으므로 바뀌지 않는다.
 */
public final class CategoryNormalizer {

    public record CategoryPair(String large, String medium) {
    }

    /** 가운뎃점을 맞춘 옛 대분류 이름 → 현재 이름 */
    private static final Map<String, String> LARGE_ALIASES = Map.of(
            dotKey("교육･직업훈련"), "교육",
            dotKey("금융･복지･문화"), "복지문화",
            dotKey("참여･기반"), "참여권리");

    /** 가운뎃점을 맞춘 옛 중분류 이름 → 현재 이름 (중분류 이름은 대분류 사이에서 겹치지 않는다) */
    private static final Map<String, String> MEDIUM_ALIASES = Map.of(
            dotKey("온·오프라인교육"), "온라인교육",
            dotKey("문화활동 및 생활지원"), "문화활동");

    private CategoryNormalizer() {
    }

    public static List<CategoryPair> split(String large, String medium) {
        List<String> larges = tokens(large).stream().map(value -> canonical(LARGE_ALIASES, value)).toList();
        List<String> mediums = tokens(medium).stream().map(value -> canonical(MEDIUM_ALIASES, value)).toList();
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

    /** 옛 대분류 이름 (가운뎃점을 ·로 맞춘 값). 저장된 행을 찾을 때 SQL에서 translate(이름, '･・ㆍ', '···')와 비교한다 */
    public static Set<String> legacyLargeKeys() {
        return LARGE_ALIASES.keySet();
    }

    /** 옛 중분류 이름 (가운뎃점을 ·로 맞춘 값) */
    public static Set<String> legacyMediumKeys() {
        return MEDIUM_ALIASES.keySet();
    }

    /** 가운뎃점 모양(･ U+FF65, · U+00B7, ・ U+30FB, ㆍ U+318D)을 ·로 맞춘다 */
    static String dotKey(String value) {
        return value.replaceAll("[\uFF65\u00B7\u30FBㆍ]", "\u00B7");
    }

    private static String canonical(Map<String, String> aliases, String value) {
        return aliases.getOrDefault(dotKey(value), value);
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
