package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.region.domain.entity.Region;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB 없이 실행되는 DTO ↔ 엔티티 변환 테스트 */
class EligibilityProfileDtoTest {

    private final Region seoul = Region.builder().regionCode("11").regionName("서울특별시").build();
    private final Region gangnam = Region.builder().regionCode("11680").regionName("강남구").parent(seoul).build();

    @Test
    void 미입력_모름_해당없음이_요청에서_응답까지_구분되어_유지된다() {
        EligibilityProfileUpdateRequest request = request(
                new ProfileAnswers.Income(AnswerStatus.PROVIDED, new BigDecimal("1800000"), IncomePeriod.MONTHLY),
                new ProfileAnswers.Income(AnswerStatus.UNKNOWN, null, null),
                new ProfileAnswers.SpecialTargets(AnswerStatus.NOT_APPLICABLE, null)
        );

        EligibilityProfileResponse response = EligibilityProfileResponse.from(request.toEntity(1L, gangnam, null));

        assertThat(response.residenceRegionCode()).isEqualTo("11680");
        assertThat(response.personalIncome().amount()).isEqualByComparingTo("1800000");
        assertThat(response.householdIncome().status()).isEqualTo(AnswerStatus.UNKNOWN);
        assertThat(response.medianIncome()).isNull();            // 미입력
        assertThat(response.maritalStatus()).isNull();           // 미입력
        assertThat(response.specialTargets().status()).isEqualTo(AnswerStatus.NOT_APPLICABLE);
        assertThat(response.specialTargets().targets()).isEmpty();
    }

    @Test
    void 소득에는_해당_없음을_쓸_수_없다() {
        EligibilityProfileUpdateRequest request = request(
                new ProfileAnswers.Income(AnswerStatus.NOT_APPLICABLE, null, null), null, null);

        assertThatThrownBy(() -> request.toEntity(1L, gangnam, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 전체_교체시_빠진_항목은_미입력이_되고_특화대상도_교체된다() {
        EligibilityProfile saved = request(null, null,
                new ProfileAnswers.SpecialTargets(AnswerStatus.PROVIDED, Set.of(SpecialTarget.DISABLED)))
                .toEntity(1L, gangnam, null);

        saved.overwrite(request(null, null,
                new ProfileAnswers.SpecialTargets(AnswerStatus.PROVIDED, Set.of(SpecialTarget.SINGLE_PARENT)))
                .toEntity(1L, gangnam, null));

        assertThat(saved.getSpecialTargets()).containsExactly(SpecialTarget.SINGLE_PARENT);
        assertThat(saved.getPersonalIncome()).isNull();
    }

    private EligibilityProfileUpdateRequest request(ProfileAnswers.Income personal,
                                                    ProfileAnswers.Income household,
                                                    ProfileAnswers.SpecialTargets specialTargets) {
        return new EligibilityProfileUpdateRequest(
                LocalDate.of(1998, 5, 15), "11680",
                EmploymentStatus.UNEMPLOYED, EducationLevel.UNIVERSITY, EnrollmentStatus.GRADUATED,
                null, null, HousingStatus.MONTHLY_RENT, YesNoUnknown.YES,
                personal, household, null, null, 1, null, null,
                specialTargets, null, null, null, null);
    }
}
