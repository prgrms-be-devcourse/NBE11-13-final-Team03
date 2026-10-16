package com.team3.backend.collection.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** 변경 시점의 원본 스냅샷. syncHistory는 이 스냅샷을 생성한 실행이다. */
@Entity
@Table(name = "raw_external_record", indexes = {
        @Index(name = "idx_raw_source_external_id", columnList = "source_id, external_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawExternalRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false, updatable = false)
    private ExternalSource source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sync_id", nullable = false, updatable = false)
    private SyncHistory syncHistory;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "payload_hash", nullable = false, updatable = false)
    private String payloadHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    public RawExternalRecord(SyncHistory syncHistory, String externalId, String payloadHash,
                             JsonNode payload, OffsetDateTime observedAt) {
        this.syncHistory = syncHistory;
        this.source = syncHistory.getSource();
        this.externalId = externalId;
        this.payloadHash = payloadHash;
        this.payload = payload.deepCopy();
        this.firstSeenAt = observedAt;
        this.lastSeenAt = observedAt;
    }

    public void markSeenAt(OffsetDateTime observedAt) {
        if (observedAt.isBefore(lastSeenAt)) {
            throw new IllegalArgumentException("마지막 관측 시각 이전의 원본은 적용할 수 없습니다.");
        }
        this.lastSeenAt = observedAt;
    }
}
