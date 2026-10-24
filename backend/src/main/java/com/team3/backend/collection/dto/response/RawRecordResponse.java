package com.team3.backend.collection.dto.response;

import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

public record RawRecordResponse(
        Long rawRecordId,
        String source,
        String externalId,
        String payloadHash,
        OffsetDateTime collectedAt,
        OffsetDateTime lastSeenAt,
        JsonNode payload
) {
}
