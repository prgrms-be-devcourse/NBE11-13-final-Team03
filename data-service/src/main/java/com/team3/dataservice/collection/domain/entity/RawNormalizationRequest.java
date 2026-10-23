package com.team3.dataservice.collection.domain.entity;

import com.team3.dataservice.collection.domain.RecordResultType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 수집 파트가 등록하는 DB polling용 정제 요청. 처리·선점 로직은 포함하지 않는다. */
@Entity
@Table(name = "raw_normalization_request",
        uniqueConstraints = @UniqueConstraint(name = "uk_normalization_request_raw", columnNames = "raw_record_id"),
        indexes = @Index(name = "idx_normalization_request_status_id", columnList = "status, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawNormalizationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raw_record_id", nullable = false, updatable = false)
    private RawExternalRecord rawRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_raw_record_id", updatable = false)
    private RawExternalRecord previousRawRecord;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 16)
    private RecordResultType eventType;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public RawNormalizationRequest(RawExternalRecord rawRecord, RawExternalRecord previousRawRecord,
                                    RecordResultType eventType, OffsetDateTime createdAt) {
        if (eventType != RecordResultType.CREATED && eventType != RecordResultType.UPDATED) {
            throw new IllegalArgumentException("신규·변경 원본만 정제 요청을 등록할 수 있습니다.");
        }
        this.rawRecord = rawRecord;
        this.previousRawRecord = previousRawRecord;
        this.eventType = eventType;
        this.createdAt = createdAt;
    }
}
