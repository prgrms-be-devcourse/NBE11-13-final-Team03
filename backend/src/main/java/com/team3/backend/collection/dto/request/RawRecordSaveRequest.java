package com.team3.backend.collection.dto.request;

import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/** payloadHash is retained for HTTP compatibility; the data-service calculates it. */
public record RawRecordSaveRequest(Long syncId, String externalId, String payloadHash,
                                   JsonNode payload, OffsetDateTime collectedAt) {}
