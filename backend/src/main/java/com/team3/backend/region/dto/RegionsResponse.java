package com.team3.backend.region.dto;

import java.util.List;

/** 지역 사전 전체 (코드 오름차순). 사전이 비어 있으면 빈 배열 */
public record RegionsResponse(
        List<RegionResponse> regions
) {
}
