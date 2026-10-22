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
 * 사용자 × 조건 묶음의 최신 신청 가능성 판정 결과 (ERD ELIGIBILITY_RESULT). 사용자·조건 묶음당 1건만 두고 재판정 시 갱신한다.
 * 정책 엔진(EligibilityResultService)만 쓰고, 추천·검색은 EligibilityProvider·EligibilitySpecifications로 읽기만 한다.
 *
 * 조건 묶음(opportunity ConditionSet): 판정 입력(자격 조건 + 거주지역)이 같은 기회 버전의 묶음. 같은 묶음이면 판정 결과가 같아
 * 기회마다 저장하지 않고 묶음마다 한 번만 저장한다 (실데이터 2026-10-21: 기회 141,236 → 묶음 1,672).
 * 기회의 결과 = 기회의 현재 버전이 속한 조건 묶음의 결과.
 *
 * 최신 여부: 아래가 모두 현재와 같고 오늘이 validUntil 전이면 최신이다.
 * - conditionSetId: 기회의 현재 버전의 조건 묶음 (기회에 새 버전이 생겨도 묶음이 같으면 다시 판정하지 않는다)
 * - profileVersion: 판정 프로필의 판정 입력 버전
 * - ruleVersion: 판정 규칙 버전 (EligibilityResultService.RULE_VERSION)
 * - validUntil: 만 나이가 바뀌는 다음 생일 (그날부터 다시 판정)
 *
 * 조건별 결과 수(matchedCount 등)는 조건별 결과(conditionResults)의 result 값을 센 것이다 (목록 표시·정렬용, jsonb를 읽지 않아도 됨).
 * 테이블 이름이 이전(eligibility_results, 사용자 × 기회)과 다르다. 이전 테이블은 쓰지 않으며 운영 DB에서 지워도 된다.
 */
@Entity
@Table(
        name = "eligibility_set_results",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_eligibility_set_results_user_set",
                columnNames = {"user_id", "condition_set_id"}
        ),
        indexes = @Index(name = "idx_eligibility_set_results_user_status", columnList = "user_id, eligibility_status")
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

    /** opportunity ConditionSet ID */
    @Column(name = "condition_set_id", nullable = false)
    private Long conditionSetId;

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

    /** 조건별 결과 중 MATCHED 수 */
    @Builder.Default
    @Column(name = "matched_count", nullable = false)
    private int matchedCount = 0;

    /** 조건별 결과 중 NOT_MATCHED 수 */
    @Builder.Default
    @Column(name = "not_matched_count", nullable = false)
    private int notMatchedCount = 0;

    /** 조건별 결과 중 USER_INFO_MISSING 수 (사용자 정보가 없어 판단 못 함) */
    @Builder.Default
    @Column(name = "user_info_missing_count", nullable = false)
    private int userInfoMissingCount = 0;

    /** 조건별 결과 중 CHECK_REQUIRED 수 (원문 확인 필요) */
    @Builder.Default
    @Column(name = "check_required_count", nullable = false)
    private int checkRequiredCount = 0;

    /** 조건별 결과 중 DATA_UNCLEAR 수 (정책 정보 불명확) */
    @Builder.Default
    @Column(name = "data_unclear_count", nullable = false)
    private int dataUnclearCount = 0;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;
}
