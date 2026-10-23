package com.team3.dataservice.collection.dto.response;

public record SyncPageFailureResponse(
        int pageNumber,
        String errorCode,
        String errorMessage,
        boolean retryable
) {}

