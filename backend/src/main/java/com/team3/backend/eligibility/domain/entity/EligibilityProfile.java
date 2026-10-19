package com.team3.backend.eligibility.domain.entity;

import com.team3.backend.region.domain.entity.Region;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 신청 가능성 판정용 사용자 프로필 (사용자당 1개).
 *
 * 응답 상태 구분: NULL = 미입력, UNKNOWN = 모름, NOT_APPLICABLE = 해당 없음.
 * 미입력·모름은 판정에서 POSSIBLY_ELIGIBLE로 이어지며 충족/불충족으로 임의 처리하지 않는다.
 * 소득 지표(개인 소득, 가구 소득, 기준중위소득 비율, 학자금 지원구간)는 서로 다른 값이므로 따로 저장한다.
 *
 * 관심 분야·희망 활동지역·희망 훈련 조건 등 추천용 항목은 포함하지 않는다.
 * user_id는 User 엔티티가 병합되기 전이라 ID 값으로만 보관한다. (병합 후 연관관계 전환)
 */
@Entity
@Table(name = "eligibility_profiles")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EligibilityProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    // ── 공통 (필수) ──
    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    /** 시·군·구 코드 (상위 시·도는 regions.parent로 조회) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "residence_region_code",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_eligibility_profiles_residence_region")
    )
    private Region residenceRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 30)
    private EmploymentStatus employmentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "education_level", nullable = false, length = 30)
    private EducationLevel educationLevel;

    // ── 공통 (해당 시 필수 / 권장) ──
    @Enumerated(EnumType.STRING)
    @Column(name = "enrollment_status", length = 30)
    private EnrollmentStatus enrollmentStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "school_region_code",
            foreignKey = @ForeignKey(name = "fk_eligibility_profiles_school_region")
    )
    private Region schoolRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "marital_status", length = 20)
    private MaritalStatus maritalStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "housing_status", length = 20)
    private HousingStatus housingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "homeless_status", length = 20)
    private YesNoUnknown homelessStatus;

    // ── 온통청년 추가 (선택) ──
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "status", column = @Column(name = "personal_income_status", length = 20)),
            @AttributeOverride(name = "amount", column = @Column(name = "personal_income_amount", precision = 15, scale = 0)),
            @AttributeOverride(name = "period", column = @Column(name = "personal_income_period", length = 20))
    })
    private IncomeAnswer personalIncome;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "status", column = @Column(name = "household_income_status", length = 20)),
            @AttributeOverride(name = "amount", column = @Column(name = "household_income_amount", precision = 15, scale = 0)),
            @AttributeOverride(name = "period", column = @Column(name = "household_income_period", length = 20))
    })
    private IncomeAnswer householdIncome;

    @Enumerated(EnumType.STRING)
    @Column(name = "median_income_status", length = 20)
    private AnswerStatus medianIncomeStatus;

    /** 기준중위소득 대비 비율(%) — medianIncomeStatus=PROVIDED일 때만 값이 있다 */
    @Column(name = "median_income_percent", precision = 5, scale = 2)
    private BigDecimal medianIncomePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "student_loan_bracket_status", length = 20)
    private AnswerStatus studentLoanBracketStatus;

    /** 학자금 지원구간 1~10 */
    @Column(name = "student_loan_bracket")
    private Integer studentLoanBracket;

    @Column(name = "household_size")
    private Integer householdSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "householder_status", length = 20)
    private YesNoUnknown householderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "major_field", length = 30)
    private MajorField majorField;

    /** NULL=미입력, NOT_APPLICABLE=해당 없음, UNKNOWN=응답 거부·모름, PROVIDED=specialTargets 사용 */
    @Enumerated(EnumType.STRING)
    @Column(name = "special_target_status", length = 20)
    private AnswerStatus specialTargetStatus;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "eligibility_profile_special_targets",
            joinColumns = @JoinColumn(name = "profile_id"),
            foreignKey = @ForeignKey(name = "fk_eligibility_profile_special_targets_profile")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "special_target", nullable = false, length = 40)
    private Set<SpecialTarget> specialTargets = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "military_service_status", length = 20)
    private MilitaryServiceStatus militaryServiceStatus;

    @Column(name = "military_service_months")
    private Integer militaryServiceMonths;

    // ── 고용24 직업훈련 ──
    @Enumerated(EnumType.STRING)
    @Column(name = "training_card_status", length = 20)
    private TrainingCardStatus trainingCardStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "kdt_participation", length = 20)
    private TrainingParticipation kdtParticipation;

    @Enumerated(EnumType.STRING)
    @Column(name = "other_training_participation", length = 20)
    private TrainingParticipation otherTrainingParticipation;

    /**
     * 판정 입력 버전. 판정에 쓰이는 값이 실제로 바뀔 때만 1씩 올라간다 (ERD USER_PROFILE.profile_version).
     * 저장된 판정 결과(eligibility_results)가 최신인지 판단하는 기준.
     */
    @Builder.Default
    @Column(name = "profile_version", nullable = false)
    private Long profileVersion = 1L;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 전체 교체 반영. id·userId·createdAt은 유지하고 나머지는 source 값으로 덮어쓴다.
     * 요청에서 빠진 항목은 미입력(NULL)이 된다. 판정에 쓰이는 값이 실제로 바뀐 경우에만 profileVersion을 올린다.
     *
     * @return 값이 바뀌었으면 true
     */
    public boolean overwrite(EligibilityProfile source) {
        boolean changed = !sameJudgmentInput(source);
        this.birthDate = source.birthDate;
        this.residenceRegion = source.residenceRegion;
        this.employmentStatus = source.employmentStatus;
        this.educationLevel = source.educationLevel;
        this.enrollmentStatus = source.enrollmentStatus;
        this.schoolRegion = source.schoolRegion;
        this.maritalStatus = source.maritalStatus;
        this.housingStatus = source.housingStatus;
        this.homelessStatus = source.homelessStatus;
        this.personalIncome = source.personalIncome;
        this.householdIncome = source.householdIncome;
        this.medianIncomeStatus = source.medianIncomeStatus;
        this.medianIncomePercent = source.medianIncomePercent;
        this.studentLoanBracketStatus = source.studentLoanBracketStatus;
        this.studentLoanBracket = source.studentLoanBracket;
        this.householdSize = source.householdSize;
        this.householderStatus = source.householderStatus;
        this.majorField = source.majorField;
        this.specialTargetStatus = source.specialTargetStatus;
        this.specialTargets.clear();
        this.specialTargets.addAll(source.specialTargets);
        this.militaryServiceStatus = source.militaryServiceStatus;
        this.militaryServiceMonths = source.militaryServiceMonths;
        this.trainingCardStatus = source.trainingCardStatus;
        this.kdtParticipation = source.kdtParticipation;
        this.otherTrainingParticipation = source.otherTrainingParticipation;
        if (changed) {
            this.profileVersion = this.profileVersion + 1;
        }
        return changed;
    }

    /** 판정 입력이 같은지 (금액·비율은 숫자 값으로, 지역은 코드로 비교) */
    private boolean sameJudgmentInput(EligibilityProfile o) {
        return Objects.equals(birthDate, o.birthDate)
                && Objects.equals(regionCode(residenceRegion), regionCode(o.residenceRegion))
                && employmentStatus == o.employmentStatus
                && educationLevel == o.educationLevel
                && enrollmentStatus == o.enrollmentStatus
                && Objects.equals(regionCode(schoolRegion), regionCode(o.schoolRegion))
                && maritalStatus == o.maritalStatus
                && housingStatus == o.housingStatus
                && homelessStatus == o.homelessStatus
                && sameIncome(personalIncome, o.personalIncome)
                && sameIncome(householdIncome, o.householdIncome)
                && medianIncomeStatus == o.medianIncomeStatus
                && sameNumber(medianIncomePercent, o.medianIncomePercent)
                && studentLoanBracketStatus == o.studentLoanBracketStatus
                && Objects.equals(studentLoanBracket, o.studentLoanBracket)
                && Objects.equals(householdSize, o.householdSize)
                && householderStatus == o.householderStatus
                && majorField == o.majorField
                && specialTargetStatus == o.specialTargetStatus
                && Objects.equals(new HashSet<>(specialTargets), new HashSet<>(o.specialTargets))
                && militaryServiceStatus == o.militaryServiceStatus
                && Objects.equals(militaryServiceMonths, o.militaryServiceMonths)
                && trainingCardStatus == o.trainingCardStatus
                && kdtParticipation == o.kdtParticipation
                && otherTrainingParticipation == o.otherTrainingParticipation;
    }

    private static String regionCode(Region region) {
        return region == null ? null : region.getRegionCode();
    }

    private static boolean sameIncome(IncomeAnswer a, IncomeAnswer b) {
        AnswerStatus as = a == null ? null : a.getStatus();
        AnswerStatus bs = b == null ? null : b.getStatus();
        if (as != bs) {
            return false;
        }
        return a == null || b == null
                || sameNumber(a.getAmount(), b.getAmount()) && a.getPeriod() == b.getPeriod();
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    @PrePersist
    protected void onCreate() {
        if (this.profileVersion == null) {
            this.profileVersion = 1L;
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
