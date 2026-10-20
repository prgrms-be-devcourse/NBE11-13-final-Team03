package com.team3.backend.eligibility.engine;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.region.domain.entity.Region;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EligibilityProfileInputTest {

    private final Region gangnam = Region.builder().regionCode("1168000000").regionName("강남구").build();

    @Test
    void 엔진이_읽는_프로필_값을_그대로_옮긴다() {
        EligibilityProfile profile = EligibilityProfile.builder()
                .userId(1L)
                .birthDate(LocalDate.of(1998, 5, 15))
                .residenceRegion(gangnam)
                .maritalStatus(MaritalStatus.SINGLE)
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY)
                .enrollmentStatus(EnrollmentStatus.GRADUATED)
                .majorField(MajorField.ENGINEERING)
                .specialTargetStatus(AnswerStatus.PROVIDED)
                .specialTargets(new HashSet<>(Set.of(SpecialTarget.WOMAN, SpecialTarget.SME_EMPLOYEE)))
                .medianIncomeStatus(AnswerStatus.PROVIDED)
                .medianIncomePercent(new BigDecimal("80"))
                .studentLoanBracketStatus(AnswerStatus.PROVIDED)
                .studentLoanBracket(3)
                .personalIncome(IncomeAnswer.provided(new BigDecimal("2000000"), IncomePeriod.MONTHLY))
                .householdIncome(IncomeAnswer.unknown())
                .trainingCardStatus(TrainingCardStatus.ISSUED)
                .kdtParticipation(TrainingParticipation.NONE)
                .build();

        EligibilityProfileInput input = EligibilityProfileInput.from(profile);

        assertThat(input.birthDate()).isEqualTo(LocalDate.of(1998, 5, 15));
        assertThat(input.residenceRegionCode()).isEqualTo("1168000000");
        assertThat(input.maritalStatus()).isEqualTo(MaritalStatus.SINGLE);
        assertThat(input.employmentStatus()).isEqualTo(EmploymentStatus.UNEMPLOYED);
        assertThat(input.educationLevel()).isEqualTo(EducationLevel.UNIVERSITY);
        assertThat(input.enrollmentStatus()).isEqualTo(EnrollmentStatus.GRADUATED);
        assertThat(input.majorField()).isEqualTo(MajorField.ENGINEERING);
        assertThat(input.specialTargets()).containsExactlyInAnyOrder(SpecialTarget.WOMAN, SpecialTarget.SME_EMPLOYEE);
        assertThat(input.medianIncomePercent()).isEqualByComparingTo("80");
        assertThat(input.studentLoanBracket()).isEqualTo(3);
        assertThat(input.personalIncome())
                .isEqualTo(new EligibilityProfileInput.Income(AnswerStatus.PROVIDED, new BigDecimal("2000000"), IncomePeriod.MONTHLY));
        assertThat(input.householdIncome())
                .isEqualTo(new EligibilityProfileInput.Income(AnswerStatus.UNKNOWN, null, null));
        assertThat(input.trainingCardStatus()).isEqualTo(TrainingCardStatus.ISSUED);
        assertThat(input.kdtParticipation()).isEqualTo(TrainingParticipation.NONE);
    }

    @Test
    void 비어_있는_값은_null로_두고_특화대상은_PROVIDED일_때만_읽는다() {
        EligibilityProfile profile = EligibilityProfile.builder()
                .userId(1L)
                .specialTargetStatus(AnswerStatus.UNKNOWN)
                .specialTargets(new HashSet<>(Set.of(SpecialTarget.WOMAN)))
                .build();

        EligibilityProfileInput input = EligibilityProfileInput.from(profile);

        assertThat(input.residenceRegionCode()).isNull();
        assertThat(input.personalIncome()).isNull();
        assertThat(input.householdIncome()).isNull();
        assertThat(input.specialTargets()).isEmpty();
    }

    @Test
    void 만든_뒤_엔티티를_바꿔도_입력은_바뀌지_않는다() {
        Set<SpecialTarget> targets = new HashSet<>(Set.of(SpecialTarget.WOMAN));
        EligibilityProfile profile = EligibilityProfile.builder()
                .userId(1L)
                .specialTargetStatus(AnswerStatus.PROVIDED)
                .specialTargets(targets)
                .build();

        EligibilityProfileInput input = EligibilityProfileInput.from(profile);
        targets.add(SpecialTarget.DISABLED);

        assertThat(input.specialTargets()).containsExactly(SpecialTarget.WOMAN);
        assertThatThrownBy(() -> input.specialTargets().add(SpecialTarget.FARMER))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
