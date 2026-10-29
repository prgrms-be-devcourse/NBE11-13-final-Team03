package com.team3.backend.collection.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record SyncJobResponse(
        Long jobId,
        String source,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        Integer totalCount,
        int fetchedCount,
        int successCount,
        int failedRecordCount,
        int pendingCount,
        int failedPageCount,
        int newCount,
        int updatedCount,
        int unchangedCount,
        List<SyncPageFailureResponse> pageFailures,
        List<Employment24PageFailureResponse> employment24PageFailures,
        List<String> incompleteNcsCodes,
        List<SyncRecordResultResponse> recordFailures,
        String failureReason
) {
    public SyncJobResponse {
        pageFailures = List.copyOf(pageFailures);
        employment24PageFailures = List.copyOf(employment24PageFailures);
        incompleteNcsCodes = List.copyOf(incompleteNcsCodes);
        recordFailures = List.copyOf(recordFailures);
    }

}
