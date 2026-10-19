package com.team3.backend.collection.dto.response;

import com.team3.backend.collection.domain.SyncJobStatus;
import java.time.OffsetDateTime;

public record SyncHistoryResponse(
        Long jobId,
        SyncJobStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        int fetchedCount,
        int successCount,
        int failedRecordCount,
        int failedPageCount
) {}

