package com.team3.backend.collection.dto.response;

import com.team3.backend.collection.domain.SyncJobStatus;
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
        List<SyncPageFailureResponse> pageFailures
) {}

