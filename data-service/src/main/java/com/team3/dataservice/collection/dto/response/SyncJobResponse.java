package com.team3.dataservice.collection.dto.response;

import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.domain.entity.SyncHistory;
import java.time.OffsetDateTime;
import java.util.List;

public record SyncJobResponse(
        Long jobId,
        String source,
        SyncJobStatus status,
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

    public static SyncJobResponse from(SyncHistory history) {
        return new SyncJobResponse(history.getId(), history.getSource().getSourceCode(), history.getStatus(),
                history.getStartedAt(), history.getFinishedAt(), null, history.getCollectedCount(),
                history.successCount(), history.getFailedCount(), history.pendingCount(), history.failedPageCount(),
                history.getNewCount(), history.getUpdatedCount(), history.getUnchangedCount(),
                history.getPageFailures(), history.getEmployment24PageFailures(), history.getIncompleteNcsCodes(),
                history.getRecordFailures(), history.getFailureReason());
    }
}

