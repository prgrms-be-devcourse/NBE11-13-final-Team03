package com.team3.backend.eligibility.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 사용자 × 기회의 최신 신청 가능성 판정 결과 (ERD ELIGIBILITY_RESULT). 사용자·기회당 1건만 두고 재판정 시 갱신한다.
 * 정책 엔진(EligibilityResultService)만 쓰고, 추천·검색은 읽기만 한다.
 *
 * 최신 여부: 아래가 모두 현재와 같고 오늘이 validUntil 전이면 최신이다.
 * - opportunityVersionId: 기회의 현재 정규화 버전
 * - profileVersion: 판정 프로필의 판정 입력 버전
 * - ruleVersion: 판정 규칙 버전 (EligibilityResultService.RULE_VERSION)
 * - validUntil: 만 나이가 바뀌는 다음 생일 (그날부터 다시 판정)
 * userId·opportunityId는 다른 영역 엔티티가 병합되기 전이라 ID 값으로만 보관한다.
 */
@Entity
@Table(
        name = "eligibility_results",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_eligibility_results_user_opportunity",
                columnNames = {"user_id", "opportunity_id"}
        ),
        indexes = @Index(name = "idx_eligibility_results_user_status", columnList = "user_id, eligibility_status")
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EligibilityResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "opportunity_id", nullable = false)
    private Long opportunityId;

    @Column(name = "opportunity_version_id", nullable = false)
    private Long opportunityVersionId;

    @Column(name = "profile_version", nullable = false)
    private Long profileVersion;

    @Column(name = "rule_version", nullable = false, length = 20)
    private String ruleVersion;

    /** 최종 판정 4가지 */
    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_status", nullable = false, length = 30)
    private EligibilityStatus eligibilityStatus;

    @Column(name = "reason", length = 500)
    private String reason;

    /** 조건별 판정 결과 (type, field, requirement, operator, expected, actual, result(ConditionMatchResult 6가지), reason) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_results", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> conditionResults;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    public void update(Long opportunityVersionId, Long profileVersion, String ruleVersion, EligibilityStatus status,
                       String reason, List<Map<String, Object>> conditionResults, LocalDate validUntil,
                       LocalDateTime evaluatedAt) {
        this.opportunityVersionId = opportunityVersionId;
        this.profileVersion = profileVersion;
        this.ruleVersion = ruleVersion;
        this.eligibilityStatus = status;
        this.reason = reason;
        this.conditionResults = conditionResults;
        this.validUntil = validUntil;
        this.evaluatedAt = evaluatedAt;
    }
}
