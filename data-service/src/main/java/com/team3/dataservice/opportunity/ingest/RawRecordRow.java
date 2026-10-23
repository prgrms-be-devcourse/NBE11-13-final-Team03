package com.team3.dataservice.opportunity.ingest;

import java.time.OffsetDateTime;

/**
 * 수집 영역 원본 한 건 (raw_external_record) + 처리 판단에 필요한 값.
 *
 * @param requestId        정제 요청 ID (raw_normalization_request.id). 주기적 대조로 찾은 원본이면 null
 * @param latest           같은 (출처, 외부 ID)의 가장 최신 원본인가. 아니면 더 새 원본이 따로 처리되므로 건너뛴다
 * @param alreadyProcessed 이 원본 해시를 이미 처리했는가 (raw_ingest_states 기준)
 */
public record RawRecordRow(
        Long requestId,
        Long rawRecordId,
        Long sourceId,
        String sourceCode,
        String externalId,
        String payloadHash,
        String payload,
        OffsetDateTime lastSeenAt,
        boolean latest,
        boolean alreadyProcessed
) {
}
