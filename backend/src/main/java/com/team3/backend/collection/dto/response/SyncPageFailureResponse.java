package com.team3.backend.collection.dto.response;

public record SyncPageFailureResponse(
        int pageNumber,
        String errorCode,
        String errorMessage,
        boolean retryable
) {}
