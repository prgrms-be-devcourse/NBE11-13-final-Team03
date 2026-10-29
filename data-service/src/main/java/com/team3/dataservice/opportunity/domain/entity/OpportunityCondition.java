package com.team3.dataservice.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 버전별 신청 자격조건. 평가 로직은 엔티티에 두지 않는다.
 *
 * expression: 조건 유형별 구조화 값. status는 항상 포함하며, 확인되지 않은 값은 넣지 않는다(NULL ≠ 제한 없음).
 *   AGE    {"status": RANGE|MIN_ONLY|MAX_ONLY|DATE_BOUND|NO_LIMIT|NOT_APPLICABLE|UNKNOWN,
 *           "min": 19, "max": 34, "bornOnOrAfter"/"bornOnOrBefore": "1986-01-01"}
 *          sprtTrgtMinAge=0, sprtTrgtMaxAge=0, sprtTrgtAgeLmtYn=Y 는 NO_LIMIT로 처리하지 않는다.
 *          근거 확인 전: status=UNKNOWN, validation_status=DATA_CONFLICT.
 *          ptcpPrpTrgtCn·addAplyQlfcCndCn·공식 공고로 확인되면 해당 상태(RANGE, NOT_APPLICABLE 등)로 저장하되
 *          evidence.dataConflict=true를 유지해 원본 모순을 남긴다.
 *          대상별로 연령이 다르면 단일 RANGE로 합치지 않고 condition_type=COMPOSITE 조건으로 저장한다:
 *          {"op":"OR","children":[{"target":"...","type":"AGE","status":"RANGE","min":13,"max":64}, ...]}
 *   INCOME {"status": RANGE|MIN_ONLY|MAX_ONLY|NO_LIMIT|COMPOSITE|UNKNOWN,
 *           "type": AMOUNT|MEDIAN_PERCENT|STUDENT_LOAN_BRACKET|OTHER, "min", "max", "minInclusive", "maxInclusive",
 *           "period": MONTHLY|YEARLY|UNKNOWN, "subject": INDIVIDUAL|HOUSEHOLD|UNKNOWN}
 *   REGION {"status": NATIONAL|SPECIFIC|UNKNOWN, "rawZipCd": "..."} (코드는 opportunity_regions)
 *   COMPOSITE 등 {"op": AND|OR|NOT, "children": [...]} 형태로 중첩·제외조건 표현
 * evidence: 근거. sourceType과 confidence는 다음 대응을 따른다:
 *   OFFICIAL_NOTICE·OFFICIAL_PAGE → HIGH, YOUTH_CENTER_DETAIL·YOUTH_CENTER_API → MEDIUM,
 *   DOCUMENT_TEXT → 출처에 따라, MANUAL_INFERENCE → LOW. API와 공식 공고가 다르면 공식 공고 우선 + dataConflict=true.
 *   {"evidenceText", "sourceType", "sourceUrl", "sourceHash", "confidence": HIGH|MEDIUM|LOW,
 *                  "dataConflict": true|false, "verifiedAt",
 *                  "rawRecordId": 수집 영역 raw_external_records.id, "sourceFields": ["sprtTrgtMinAge", ...],
 *                  "raw": {원본 필드값 그대로(문자열·빈 문자열 포함)}}
 *   API 근거이면 sourceHash에 raw payload_hash를 넣는다. 원본 값은 바꾸지 않고 그대로 보존한다.
 */
@Entity
@Immutable
@Table(
        name = "opportunity_conditions",
        indexes = @Index(name = "idx_opportunity_conditions_version", columnList = "version_id")
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpportunityCondition {

    @Id
    // 묶음 INSERT(hibernate.jdbc.batch_size)를 쓰려면 IDENTITY가 아니라 시퀀스여야 한다. 50개씩 미리 받는다 (pooled)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "opportunity_conditions_seq")
    @SequenceGenerator(name = "opportunity_conditions_seq", sequenceName = "opportunity_conditions_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "version_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_opportunity_conditions_version")
    )
    private OpportunityVersion version;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 30)
    private ConditionType conditionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_type", nullable = false, length = 30)
    private RequirementType requirementType;

    @Column(name = "condition_field", length = 100)
    private String conditionField;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expression", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> expression;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private Map<String, Object> evidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 30)
    private ValidationStatus validationStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
