package com.team3.backend.collection.dto.request;

import java.time.OffsetDateTime;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** 출처는 syncId에 연결된 수집 실행에서 결정한다. 원본 식별자와 해시는 가공하지 않는다. */
public record RawRecordSaveRequest(
        Long syncId,
        String externalId,
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
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new IllegalArgumentException("원본 비교용 해시가 필요합니다.");
        }
        Objects.requireNonNull(payload, "원본 JSON이 필요합니다.");
        if (payload.isNull() || payload.isMissingNode()) {
            throw new IllegalArgumentException("원본 JSON이 필요합니다.");
        }
        Objects.requireNonNull(collectedAt, "수집 시각이 필요합니다.");
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
