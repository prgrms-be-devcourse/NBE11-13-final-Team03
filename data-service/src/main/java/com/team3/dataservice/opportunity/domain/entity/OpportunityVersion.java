package com.team3.dataservice.opportunity.domain.entity;

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
 * - 청년정책 상세 속성(plcyExplnCn, plcySprtCn, plcyKywdNm, 기관, aplyUrlAddr, refUrlAddr1/2 등)은 "policy"로 넣고,
 *   화면용 값은 YouthPolicyDetail에 저장한다. 직업훈련 속성은 "training" + TrainingCourseDetail.
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
        ),
        indexes = @Index(name = "idx_opportunity_versions_condition_set", columnList = "condition_set_id")
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpportunityVersion {

    @Id
    // 묶음 INSERT(hibernate.jdbc.batch_size)를 쓰려면 IDENTITY가 아니라 시퀀스여야 한다. 50개씩 미리 받는다 (pooled)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "opportunity_versions_seq")
    @SequenceGenerator(name = "opportunity_versions_seq", sequenceName = "opportunity_versions_seq", allocationSize = 50)
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

    /**
     * 조건 묶음 ID (ConditionSet). 판정 입력(자격 조건 + 거주지역)이 같은 버전은 같은 값.
     * 정규화가 버전을 만들 때 넣는다. 이 컬럼이 생기기 전에 만든 버전은 NULL이며 ConditionSetBackfill이 채운다.
     * 연관관계(@ManyToOne)가 아니라 ID로 둔다: 버전을 읽을 때 조건 묶음을 함께 로딩할 일이 없고(판정·조회는 ID로 조인),
     * 백필이 네이티브 UPDATE로 이 값만 채우기 때문이다. 조건 묶음 행은 지우지 않으므로 FK 없이도 고아 ID가 생기지 않는다.
     * 인덱스: 백필의 "묶음이 빈 버전" 조회(IS NULL)와 묶음 단위 판정 조회용
     */
    @Column(name = "condition_set_id")
    private Long conditionSetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
