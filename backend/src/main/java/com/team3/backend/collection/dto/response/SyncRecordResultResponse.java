package com.team3.backend.collection.dto.response;

import com.team3.backend.collection.domain.RecordProcessStatus;
import com.team3.backend.collection.domain.RecordResultType;
import java.time.OffsetDateTime;

public record SyncRecordResultResponse(
        Long recordResultId,
        String externalId,
        Integer pageNumber,
        Integer itemIndex,
        Long rawRecordId,
        RecordProcessStatus status,
        RecordResultType resultType,
        String errorCode,
        String errorMessage,
        OffsetDateTime processedAt
) {}

