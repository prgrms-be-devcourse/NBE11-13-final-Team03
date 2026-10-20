package com.team3.backend.collection.dto.external;

import com.team3.backend.collection.dto.response.Employment24PageFailureResponse;
import com.team3.backend.collection.dto.response.SyncPageFailureResponse;
import java.util.List;

/** 페이지 처리 방식의 종료 결과. 원본 목록 전체는 보관하지 않는다. */
public record CollectionSummary(
        int fetchedCount,
        boolean completed,
        List<SyncPageFailureResponse> pageFailures,
        List<Employment24PageFailureResponse> employment24PageFailures,
        List<String> incompleteNcsCodes
) {
    public CollectionSummary {
        pageFailures = List.copyOf(pageFailures);
        employment24PageFailures = List.copyOf(employment24PageFailures);
        incompleteNcsCodes = List.copyOf(incompleteNcsCodes);
    }
}
