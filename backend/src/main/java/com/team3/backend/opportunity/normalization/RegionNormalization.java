package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;

import java.util.List;

/**
 * 지역 정규화 결과.
 * regionCodes: regions 사전의 10자리 코드 (상위로 묶은 뒤, 정렬). NATIONAL·UNKNOWN이면 비어 있다.
 * unmappedCodes: 사전에서 찾지 못한 원본 코드 (통합 전 옛 코드 등) — 버리지 않고 보존
 */
public record RegionNormalization(
        RegionStatus status,
        List<String> regionCodes,
        List<String> unmappedCodes,
        ValidationStatus validationStatus,
        String reason,
        String rawZipCd
) {
}
