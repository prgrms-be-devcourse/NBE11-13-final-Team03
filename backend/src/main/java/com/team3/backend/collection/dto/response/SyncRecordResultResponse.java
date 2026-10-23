package com.team3.backend.collection.dto.response;

import java.time.OffsetDateTime;

public record SyncRecordResultResponse(
        Long recordResultId,
        String externalId,
        Integer pageNumber,
        Integer itemIndex,
        Long rawRecordId,
        String status,
        String resultType,
        String errorCode,
        String errorMessage,
        OffsetDateTime processedAt
) {}

