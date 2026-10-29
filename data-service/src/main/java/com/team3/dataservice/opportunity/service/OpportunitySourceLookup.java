package com.team3.dataservice.opportunity.service;

import com.team3.dataservice.opportunity.domain.entity.OpportunitySource;
import com.team3.dataservice.opportunity.domain.repository.OpportunitySourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 정규화가 (출처, 외부 ID)로 기존 공고 출처를 찾을 때 쓴다.
 * 처리기가 원본 묶음 시작에 묶음의 외부 ID를 한 번에 조회(prefetch)해 두면, 정규화는 원본마다 DB에 묻지 않고 여기서 찾는다
 * (실데이터 14만 건 적재에서 조회 14만 번, 35초 — ingest 튜닝 E3). 미리 조회하지 않은 키는 그때 DB에서 찾는다.
 * 같은 스레드(= 같은 트랜잭션) 안에서만 쓰고, 처리기가 묶음 끝에 clear한다.
 */
@Component
@RequiredArgsConstructor
public class OpportunitySourceLookup {

    private final OpportunitySourceRepository sourceRepository;
    private final ThreadLocal<Map<String, OpportunitySource>> found = new ThreadLocal<>();
    private final ThreadLocal<Set<String>> prefetched = new ThreadLocal<>();

    /** 출처별 외부 ID를 한 번에 조회해 둔다. 현재 트랜잭션 안에서 불러야 한다 (조회한 엔티티를 정규화가 그대로 고침) */
    public void prefetch(Map<Long, ? extends Collection<String>> externalIdsBySource) {
        Map<String, OpportunitySource> map = new HashMap<>();
        Set<String> keys = new HashSet<>();
        externalIdsBySource.forEach((sourceId, externalIds) -> {
            if (externalIds.isEmpty()) {
                return;
            }
            externalIds.forEach(id -> keys.add(key(sourceId, id)));
            sourceRepository.findAllForLookup(sourceId, externalIds)
                    .forEach(s -> map.put(key(s.getSourceId(), s.getExternalId()), s));
        });
        found.set(map);
        prefetched.set(keys);
    }

    public Optional<OpportunitySource> find(Long sourceId, String externalId) {
        Set<String> keys = prefetched.get();
        String key = key(sourceId, externalId);
        if (keys != null && keys.contains(key)) {
            return Optional.ofNullable(found.get().get(key));
        }
        return sourceRepository.findBySourceIdAndExternalId(sourceId, externalId);
    }

    /** 정규화가 새로 만든 출처. 같은 묶음에서 같은 키를 다시 찾으면 이것을 돌려준다 */
    public void remember(OpportunitySource source) {
        Map<String, OpportunitySource> map = found.get();
        if (map != null) {
            map.put(key(source.getSourceId(), source.getExternalId()), source);
        }
    }

    public void clear() {
        found.remove();
        prefetched.remove();
    }

    private static String key(Long sourceId, String externalId) {
        return sourceId + "\u0000" + externalId;
    }
}
