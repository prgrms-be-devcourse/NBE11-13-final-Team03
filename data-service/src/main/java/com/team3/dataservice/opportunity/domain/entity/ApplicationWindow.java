package com.team3.dataservice.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Map;

/**
 * 버전별 신청기간(복수 가능). 청년정책은 aplyPrdSeCd/aplyYmd만 근거로 하며 사업기간(bizPrdBgngYmd/bizPrdEndYmd)은 저장하지 않는다.
 * 직업훈련의 훈련기간은 신청기간이 아니므로 TrainingCourseDetail에 둔다.
 * 날짜는 미확인일 수 있으므로 nullable이다. 날짜 파싱 실패·시작일 > 종료일은 validationStatus=INVALID,
 * 출처 간 불일치는 DATA_CONFLICT, 미확인은 UNKNOWN으로 구분한다.
 * 현재 신청 상태는 저장하지 않고 조회 시점에 계산한다.
 */
@Entity
@Immutable
@Table(
        name = "application_windows",
        indexes = @Index(name = "idx_application_windows_version", columnList = "version_id")
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "version_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_application_windows_version")
    )
    private OpportunityVersion version;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 30)
    private PeriodType periodType;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** 원문 신청기간 (aplyYmd에서 이 기간에 해당하는 부분, 원본 그대로) */
    @Column(name = "period_text", columnDefinition = "text")
    private String periodText;

    /** 적용 범위(회차·지역 등). 예: {"round": "2차", "regionCodes": ["11"]}. 범위 구분이 없으면 NULL */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope", columnDefinition = "jsonb")
    private Map<String, Object> scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 30)
    private ValidationStatus validationStatus;
}
