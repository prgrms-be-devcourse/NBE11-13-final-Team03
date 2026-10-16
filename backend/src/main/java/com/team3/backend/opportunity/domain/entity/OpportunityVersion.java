package com.team3.backend.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 정규화 결과 버전. 생성 후 변경하지 않는다(과거 버전 보존).
 * 새 버전은 normalized_snapshot이 실질적으로 달라졌을 때만 만든다(동일하면 기존 버전 유지).
 *
 * normalized_snapshot 작성 규칙 (버전 비교 기준이므로 반드시 지킬 것)
 * - 기준일(오늘)에 따라 달라지는 값을 넣지 않는다: 현재 신청 상태(UPCOMING/OPEN/ALWAYS_OPEN/CLOSED/UNKNOWN),
 *   날짜 비교로 정해지는 알림 가능 여부 등은 조회 시점에 계산한다.
 * - 휘발성 메타데이터를 넣지 않는다: 수집·확인 시각(verifiedAt), lastMdfcnDt, payload_hash, raw id 등.
 *   (근거 추적용 정보는 조건의 evidence에 둔다)
 * - 사업기간(bizPrdSeCd/bizPrdBgngYmd/bizPrdEndYmd/bizPrdEtcCn)은 "businessPeriod"로 분리하며 신청기간으로 쓰지 않는다.
 * - 정책 고유 속성(plcyExplnCn, plcySprtCn, plcyKywdNm, mclsfNm, 기관, aplyUrlAddr, refUrlAddr1/2 등)은
 *   YouthPolicy 테이블이 합의되기 전까지 이 스냅샷에 둔다.
 * - "usage": 기준일과 무관한 값만 담는다. {"trackingEligible": true, "exclusionReasons": ["AGE_UNKNOWN", ...]}
 *   recommendationEligible·notificationEligible은 현재 신청 상태(OPEN/ALWAYS_OPEN/CLOSED 등)에 따라 달라지므로
 *   저장하지 않고, 조회 시점에 personalization_status·exclusionReasons·근거 신뢰도와 함께 계산한다.
 */
@Entity
@Immutable
@Table(
        name = "opportunity_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_opportunity_versions_opportunity_version_no",
                columnNames = {"opportunity_id", "version_no"}
        )
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpportunityVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "opportunity_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_opportunity_versions_opportunity")
    )
    private Opportunity opportunity;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "normalized_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> normalizedSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "personalization_status", nullable = false, length = 30)
    private PersonalizationStatus personalizationStatus;

    @Column(name = "normalization_version", nullable = false, length = 50)
    private String normalizationVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
