package com.team3.backend.collection.dto.request;

import java.time.OffsetDateTime;
import java.util.Objects;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** 출처는 syncId에서 결정한다. payloadHash는 기존 요청 호환용이며 서버가 원본으로 다시 계산한다. */
public record RawRecordSaveRequest(
        Long syncId,
        String externalId,
        @Schema(description = "기존 요청 호환용. 생략 가능하며 입력값은 사용하지 않고 서버가 원본 JSON으로 계산한다.",
                deprecated = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String payloadHash,
        JsonNode payload,
        OffsetDateTime collectedAt
) {
    public RawRecordSaveRequest {
        if (syncId == null || syncId < 1) {
            throw new IllegalArgumentException("수집 실행 ID는 1 이상이어야 합니다.");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("외부 식별자가 필요합니다.");
        }
        Objects.requireNonNull(payload, "원본 JSON이 필요합니다.");
        if (payload.isNull() || payload.isMissingNode()) {
            throw new IllegalArgumentException("원본 JSON이 필요합니다.");
        }
        Objects.requireNonNull(collectedAt, "수집 시각이 필요합니다.");
        payload = payload.deepCopy();
    }

    public RawRecordSaveRequest(Long syncId, String externalId, JsonNode payload, OffsetDateTime collectedAt) {
        this(syncId, externalId, null, payload, collectedAt);
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
