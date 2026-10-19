package com.team3.backend.collection.dto.response;

import com.team3.backend.collection.domain.SyncJobStatus;

public record SyncStartResponse(
        Long jobId,
        String source,
        SyncJobStatus status,
        String message
) {}

