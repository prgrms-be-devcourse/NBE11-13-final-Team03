package com.team3.backend.eligibility.api.dto;

import java.util.List;

/** 원격 구현에서도 그대로 쓸 수 있도록 Spring Page 대신 쓰는 페이지 응답 */
public record EligibilitySnapshotPage(
        List<EligibilitySnapshot> items,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {
}
