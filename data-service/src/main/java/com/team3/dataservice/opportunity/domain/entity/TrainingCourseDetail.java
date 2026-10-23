package com.team3.dataservice.opportunity.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/**
 * 고용24 훈련과정 회차의 버전별 상세 (OpportunityVersion과 1:1, 불변).
 * Opportunity 하나 = 훈련과정 한 회차 (OpportunitySource.externalId = "trprId:trprDegr").
 * 신청기간은 ApplicationWindow, 훈련 지역(trngAreaCd)은 OpportunityRegion(IMPLEMENTATION), 자격 조건은 OpportunityCondition에 둔다.
 *
 * 수집할 때마다 바뀌는 값(regCourseMan 수강신청인원, stdgScor 만족도, eiEmpl* 취업률)은 버전 비교 대상이 되면
 * 수집마다 새 버전이 생기므로 여기에 저장하지 않는다.
 * 항목 의미는 고용24 훈련과정 목록 API 출력 명세 기준. wkendSe 코드 값(샘플 2, 3, 9)은 코드표 확인 전이므로 원본 값 그대로 저장한다.
 * 목록 API에는 모집기간이 없다 (상세 API에서 ApplicationWindow로).
 */
@Entity
@Immutable
@Table(
        name = "training_course_details",
        uniqueConstraints = @UniqueConstraint(name = "uk_training_course_details_version", columnNames = "version_id"),
        indexes = {
                @Index(name = "idx_training_course_details_course", columnList = "course_id"),
                @Index(name = "idx_training_course_details_start", columnList = "training_start_date")
        }
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainingCourseDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "version_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_training_course_details_version")
    )
    private OpportunityVersion version;

    /** trprId 훈련과정ID. 같은 과정의 회차를 묶는 키 */
    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    /** trprDegr 훈련과정 순차(회차) */
    @Column(name = "course_round", nullable = false)
    private Integer courseRound;

    /** subTitle 부 제목 (응답 샘플상 훈련기관명) */
    @Column(name = "institution_name", length = 200)
    private String institutionName;

    /** instCd 훈련기관 코드 */
    @Column(name = "institution_code", length = 20)
    private String institutionCode;

    /** trainstCstId 훈련기관ID (상세 링크 파라미터 trainstCstmrId) */
    @Column(name = "institution_customer_id", length = 20)
    private String institutionCustomerId;

    /** traStartDate 훈련 시작일 */
    @Column(name = "training_start_date")
    private LocalDate trainingStartDate;

    /** traEndDate 훈련 종료일 */
    @Column(name = "training_end_date")
    private LocalDate trainingEndDate;

    /** realMan 실제 훈련비 */
    @Column(name = "actual_training_cost")
    private Long actualTrainingCost;

    /** courseMan 수강비 */
    @Column(name = "course_fee")
    private Long courseFee;

    /** yardMan 정원 */
    @Column(name = "capacity")
    private Integer capacity;

    /** ncsCd NCS 코드 (8자리) */
    @Column(name = "ncs_code", length = 20)
    private String ncsCode;

    /** trainTargetCd 훈련구분 코드 (예: C0061, C0054Y, C0055) */
    @Column(name = "training_target_code", length = 20)
    private String trainingTargetCode;

    /** trainTarget 훈련대상 */
    @Column(name = "training_target_name", length = 100)
    private String trainingTargetName;

    /** wkendSe 주말/주중 구분 코드 (원본 값, 코드표 확인 필요) */
    @Column(name = "weekend_type_code", length = 10)
    private String weekendTypeCode;

    /** address 주소 (원문) */
    @Column(name = "address", length = 500)
    private String address;

    /** telNo 전화번호 */
    @Column(name = "tel_no", length = 50)
    private String telNo;
}
