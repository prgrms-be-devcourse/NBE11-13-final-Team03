package com.team3.dataservice.collection.dto.response;

import com.team3.dataservice.collection.domain.entity.RawExternalRecord;
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
