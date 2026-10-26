package com.team3.notification.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

public record CollectionCompletedMessage(int schemaVersion, UUID messageId, String eventType,
        UUID correlationId, Instant occurredAt, Payload payload) {
    public CollectionCompletedMessage {
        if ((schemaVersion != 1 && schemaVersion != 2) || !"COLLECTION_COMPLETED".equals(eventType)
                || messageId == null || correlationId == null || occurredAt == null || payload == null) {
            throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_MESSAGE");
        }
        if ((schemaVersion == 1) != (payload.jobId() == null)) {
            throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_MESSAGE");
        }
    }

    // Omitting absent v2 fields preserves fingerprints already stored for v1 messages.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Payload(LocalDate collectedDate, String dataSource, int newCount, int updatedCount,
                          int unchangedCount, int failedCount, Long jobId, String syncType, String status,
                          Integer failedPageCount, Long durationMillis) {
        public Payload {
            Objects.requireNonNull(collectedDate);
            if (collectedDate.getYear() < 1 || collectedDate.getYear() > 9999 || dataSource == null
                    || !Set.of("YOUTH_POLICY", "EMPLOYMENT24").contains(dataSource)
                    || newCount < 0 || updatedCount < 0 || unchangedCount < 0 || failedCount < 0) {
                throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_PAYLOAD");
            }
            if (jobId != null || syncType != null || status != null || failedPageCount != null || durationMillis != null) {
                if (jobId == null || jobId < 1 || syncType == null || status == null
                        || !Set.of("SCHEDULED", "MANUAL", "MANUAL_RETRY").contains(syncType)
                        || !Set.of("SUCCESS", "PARTIAL_SUCCESS", "FAILED", "INTERRUPTED").contains(status)
                        || failedPageCount == null || failedPageCount < 0 || durationMillis == null || durationMillis < 0
                        || ("SUCCESS".equals(status) && (failedCount > 0 || failedPageCount > 0))) {
                    throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_PAYLOAD");
                }
            }
        }
    }

    public static CollectionCompletedMessage decode(ObjectMapper mapper, String body) {
        try {
            var tree = mapper.readTree(body);
            if (tree == null || !tree.isObject() || tree.size() != 6
                    || !tree.path("schemaVersion").isIntegralNumber() || !tree.path("schemaVersion").canConvertToInt()
                    || !tree.path("occurredAt").isString()) throw new IllegalArgumentException();
            for (var name : Set.of("messageId", "correlationId")) {
                if (!tree.path(name).isString() || tree.path(name).asString().length() != 36) throw new IllegalArgumentException();
            }
            var payload = tree.path("payload");
            int version = tree.path("schemaVersion").asInt();
            if ((version != 1 && version != 2) || !payload.isObject()
                    || payload.size() != (version == 1 ? 6 : 11) || !payload.path("collectedDate").isString()) {
                throw new IllegalArgumentException();
            }
            for (var name : Set.of("newCount", "updatedCount", "unchangedCount", "failedCount")) {
                if (!payload.path(name).isIntegralNumber() || !payload.path(name).canConvertToInt()) throw new IllegalArgumentException();
            }
            if (version == 2) {
                for (var name : Set.of("jobId", "durationMillis")) {
                    if (!payload.path(name).isIntegralNumber() || !payload.path(name).canConvertToLong()) throw new IllegalArgumentException();
                }
                if (!payload.path("failedPageCount").isIntegralNumber() || !payload.path("failedPageCount").canConvertToInt()
                        || !payload.path("syncType").isString() || !payload.path("status").isString()) throw new IllegalArgumentException();
            }
            return mapper.readerFor(CollectionCompletedMessage.class).with(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .without(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .readValue(body);
        } catch (RuntimeException failure) {
            // Jackson errors can contain the raw body. Never expose or retain them.
            throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_MESSAGE");
        }
    }
}
