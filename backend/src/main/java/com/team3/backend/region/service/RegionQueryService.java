package com.team3.backend.region.service;

import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.region.dto.RegionResponse;
import com.team3.backend.region.dto.RegionsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;

/**
 * 프로필 지역 선택용 지역 사전 목록.
 * 프로필 저장 검증(EligibilityProfileService)과 같은 색인(RegionDirectory)을 써서, 목록의 leaf와 거주지 허용 기준이 어긋나지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class RegionQueryService {

    private final RegionDirectory regionDirectory;

    public RegionsResponse getRegions() {
        RegionIndex index = regionDirectory.index();
        return new RegionsResponse(index.entries().stream()
                .sorted(Comparator.comparing(RegionIndex.Entry::code))
                .map(e -> new RegionResponse(e.code(), e.name(), parentInDirectory(index, e),
                        index.children(e.code()).isEmpty()))
                .toList());
    }

    /** 상위 코드가 사전에 없으면 최상위로 본다 (RegionIndex.topLevel과 같은 기준). 프론트에서 부모 없는 하위 항목이 생기지 않게 */
    private static String parentInDirectory(RegionIndex index, RegionIndex.Entry entry) {
        return entry.parentCode() != null && index.contains(entry.parentCode()) ? entry.parentCode() : null;
    }
}
