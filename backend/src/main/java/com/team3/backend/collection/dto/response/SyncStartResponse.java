package com.team3.backend.collection.dto.response;


public record SyncStartResponse(
        Long jobId,
        String source,
        String status,
        String message
) {}

