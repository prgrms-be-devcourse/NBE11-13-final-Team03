package com.team3.dataservice.collection.dto.response;

import com.team3.dataservice.collection.domain.SyncJobStatus;

public record SyncStartResponse(
        Long jobId,
        String source,
        SyncJobStatus status,
        String message
) {}

