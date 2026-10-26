package com.team3.notification.admin;

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
        if (schemaVersion != 1 || !"COLLECTION_COMPLETED".equals(eventType)
                || messageId == null || correlationId == null || occurredAt == null || payload == null) {
            throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_MESSAGE");
        }
    }

    public record Payload(LocalDate collectedDate, String dataSource, int newCount, int updatedCount,
                          int unchangedCount, int failedCount) {
        public Payload {
            Objects.requireNonNull(collectedDate);
            if (collectedDate.getYear() < 1 || collectedDate.getYear() > 9999 || dataSource == null
                    || !Set.of("YOUTH_POLICY", "EMPLOYMENT24").contains(dataSource)
                    || newCount < 0 || updatedCount < 0 || unchangedCount < 0 || failedCount < 0) {
                throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_PAYLOAD");
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
            if (!payload.isObject() || payload.size() != 6 || !payload.path("collectedDate").isString()) {
                throw new IllegalArgumentException();
            }
            for (var name : Set.of("newCount", "updatedCount", "unchangedCount", "failedCount")) {
                if (!payload.path(name).isIntegralNumber() || !payload.path(name).canConvertToInt()) throw new IllegalArgumentException();
            }
            return mapper.readerFor(CollectionCompletedMessage.class).with(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(body);
        } catch (RuntimeException failure) {
            // Jackson errors can contain the raw body. Never expose or retain them.
            throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_MESSAGE");
        }
    }
}
