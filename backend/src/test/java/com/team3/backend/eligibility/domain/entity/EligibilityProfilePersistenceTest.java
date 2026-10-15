package com.team3.backend.eligibility.domain.entity;

import com.team3.backend.opportunity.domain.entity.Region;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 PostgreSQL에서만 실행된다. 일회용 DB를 YON_TEST_DB_URL로 지정할 것 (ddl-auto=create-drop).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "YON_TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${YON_TEST_DB_URL}",
        "spring.datasource.username=${YON_TEST_DB_USERNAME:postgres}",
        "spring.datasource.password=${YON_TEST_DB_PASSWORD:1234}",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EligibilityProfilePersistenceTest {

    @PersistenceContext
    private EntityManager em;

    @Test
    void 미입력과_모름을_구분해_저장한다() {
        Region seoul = Region.builder().regionCode("11").regionName("서울특별시").build();
        Region gangnam = Region.builder().regionCode("11680").regionName("강남구").parent(seoul).build();
        em.persist(seoul);
        em.persist(gangnam);

        EligibilityProfile profile = EligibilityProfile.builder()
                .userId(1L)
                .birthDate(LocalDate.of(2000, 5, 1))
                .residenceRegion(gangnam)
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY)
                .enrollmentStatus(EnrollmentStatus.ENROLLED)
                .personalIncome(IncomeAnswer.provided(new BigDecimal("2500000"), IncomePeriod.MONTHLY))
                .householdIncome(IncomeAnswer.unknown())
                .specialTargetStatus(AnswerStatus.PROVIDED)
                .specialTargets(new java.util.HashSet<>(Set.of(SpecialTarget.SINGLE_PARENT)))
                .build();
        em.persist(profile);
        em.flush();
        em.clear();

        EligibilityProfile found = em.find(EligibilityProfile.class, profile.getId());
        assertThat(found.getResidenceRegion().getParent().getRegionCode()).isEqualTo("11");
        assertThat(found.getPersonalIncome().getAmount()).isEqualByComparingTo("2500000");
        assertThat(found.getHouseholdIncome().getStatus()).isEqualTo(AnswerStatus.UNKNOWN);
        assertThat(found.getHouseholdIncome().getAmount()).isNull();
        assertThat(found.getMaritalStatus()).isNull(); // 미입력
        assertThat(found.getMedianIncomeStatus()).isNull(); // 미입력
        assertThat(found.getSpecialTargets()).containsExactly(SpecialTarget.SINGLE_PARENT);
    }

    @Test
    void 소득_응답은_금액과_기간이_있어야_한다() {
        assertThatThrownBy(() -> IncomeAnswer.provided(null, IncomePeriod.YEARLY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IncomeAnswer.provided(new BigDecimal("-1"), IncomePeriod.YEARLY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
