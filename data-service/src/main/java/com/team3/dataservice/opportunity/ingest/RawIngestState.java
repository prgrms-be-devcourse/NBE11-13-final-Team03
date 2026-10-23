package com.team3.dataservice.opportunity.ingest;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 수집 원본을 어디까지 정규화했는지 (정책 엔진 소유). (출처, 외부 ID)마다 마지막으로 처리한 원본 해시를 둔다.
 * 주기적 대조는 "최신 원본 해시 ≠ 여기 해시"인 원본을 다시 정규화한다.
 * 정규화 성공(NORMALIZED)과 원본 데이터 문제(INVALID, 다시 해도 같음)만 기록한다. 예외로 실패한 원본은 기록하지 않아 다음 대조 때 다시 시도한다.
 * 정규화 규칙을 고친 뒤 INVALID였던 원본을 다시 처리하려면 해당 행을 지운다.
 * 쓰기는 RawRecordReader의 UPSERT SQL로만 한다 (이 엔티티는 테이블 정의용).
 */
@Entity
@Table(
        name = "raw_ingest_states",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_raw_ingest_states_source_external",
                columnNames = {"source_id", "raw_external_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawIngestState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** external_source.id */
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** raw_external_record.external_id (수집 영역 형식 그대로) */
    @Column(name = "raw_external_id", nullable = false, length = 255)
    private String rawExternalId;

    @Column(name = "raw_record_id", nullable = false)
    private Long rawRecordId;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    /** NORMALIZED 또는 INVALID */
    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;
}
