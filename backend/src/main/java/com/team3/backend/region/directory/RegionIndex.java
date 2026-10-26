package com.team3.backend.region.directory;

import java.util.*;

/**
 * 지역 사전의 읽기 전용 색인 (코드 → 이름·상위 코드, 상위 → 하위 목록).
 * 정규화·매칭에서 DB를 반복 조회하지 않도록 메모리에 둔다.
 */
public final class RegionIndex {

    public record Entry(String code, String name, String parentCode) {
    }

    private final Map<String, Entry> byCode;
    private final Map<String, Set<String>> children;
    private final Set<String> topLevel;

    public RegionIndex(Collection<Entry> entries) {
        Map<String, Entry> map = new HashMap<>();
        Map<String, Set<String>> childMap = new HashMap<>();
        Set<String> tops = new TreeSet<>();
        for (Entry e : entries) {
            map.put(e.code(), e);
        }
        for (Entry e : entries) {
            if (e.parentCode() == null || !map.containsKey(e.parentCode())) {
                tops.add(e.code());
            } else {
                childMap.computeIfAbsent(e.parentCode(), k -> new TreeSet<>()).add(e.code());
            }
        }
        this.byCode = Map.copyOf(map);
        this.children = Map.copyOf(childMap);
        this.topLevel = Collections.unmodifiableSet(tops);
    }

    /** 모든 지역 (순서 없음) */
    public Collection<Entry> entries() {
        return byCode.values();
    }

    public boolean contains(String code) {
        return byCode.containsKey(code);
    }

    public Optional<Entry> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public Set<String> children(String code) {
        return children.getOrDefault(code, Set.of());
    }

    /** 시·도 (세종처럼 상위가 없는 시·군·구 포함) */
    public Set<String> topLevel() {
        return topLevel;
    }

    /** 자기 자신부터 최상위까지의 코드 */
    public List<String> ancestorsAndSelf(String code) {
        List<String> chain = new ArrayList<>();
        String current = code;
        while (current != null && byCode.containsKey(current) && !chain.contains(current)) {
            chain.add(current);
            current = byCode.get(current).parentCode();
        }
        return chain;
    }

    public boolean isEmpty() {
        return byCode.isEmpty();
    }
}
