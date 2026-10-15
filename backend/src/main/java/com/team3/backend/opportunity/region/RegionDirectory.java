package com.team3.backend.opportunity.region;

import com.team3.backend.opportunity.domain.entity.Region;
import com.team3.backend.opportunity.domain.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지역 사전 색인을 메모리에 두고 제공한다. 지역 코드 동기화 후 {@link #refresh()}로 다시 읽는다.
 */
@Component
@RequiredArgsConstructor
public class RegionDirectory {

    private final RegionRepository regionRepository;
    private volatile RegionIndex index;

    @Transactional(readOnly = true)
    public RegionIndex index() {
        RegionIndex current = index;
        if (current == null || current.isEmpty()) {
            current = load();
            index = current;
        }
        return current;
    }

    public void refresh() {
        index = null;
    }

    private RegionIndex load() {
        return new RegionIndex(regionRepository.findAll().stream()
                .map(r -> new RegionIndex.Entry(r.getRegionCode(), r.getRegionName(), parentCode(r)))
                .toList());
    }

    private static String parentCode(Region region) {
        return region.getParent() == null ? null : region.getParent().getRegionCode();
    }
}
