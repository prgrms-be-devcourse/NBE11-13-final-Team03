package com.team3.backend.collection.dto.response;

import com.team3.backend.collection.domain.entity.RawExternalRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

public record RawRecordResponse(
        Long rawRecordId,
        String source,
        String externalId,
        String payloadHash,
        @Schema(description = "이 원본 스냅샷의 최초 관측 시각") OffsetDateTime collectedAt,
        @Schema(description = "동일 원본의 최종 관측 시각") OffsetDateTime lastSeenAt,
        @Schema(description = "정규화 이전 외부 API 원본 JSON", implementation = java.util.Map.class) JsonNode payload
) {
    /** collectedAt은 이 원본 스냅샷의 최초 관측 시각이다. source는 출처 코드다. */
    public static RawRecordResponse from(RawExternalRecord record) {
        return new RawRecordResponse(
                record.getId(),
                record.getSource().getSourceCode(),
                record.getExternalId(),
                record.getPayloadHash(),
                record.getFirstSeenAt(),
                record.getLastSeenAt(),
                record.getPayload().deepCopy()
        );
    }
}
