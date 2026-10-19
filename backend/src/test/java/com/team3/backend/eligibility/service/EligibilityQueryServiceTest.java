package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.dto.EligibilityListResponse;
import com.team3.backend.eligibility.dto.EligibilityResponse;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.dto.NormalizationCommand;
import com.team3.backend.opportunity.dto.NormalizationResult;
import com.team3.backend.opportunity.dto.YouthPolicyRaw;
import com.team3.backend.opportunity.normalization.*;
import com.team3.backend.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.backend.opportunity.normalization.official.OfficialSupplementer;
import com.team3.backend.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.backend.opportunity.service.YouthPolicyNormalizationService;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정규화 → 판정까지 실제 PostgreSQL로 검증한다. 일회용 DB를 YON_TEST_DB_URL로 지정할 것 (ddl-auto=create-drop).
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
@Import({EligibilityQueryService.class, EligibilityEvaluator.class, EligibilityEngine.class, ApplicationStatusCalculator.class,
        YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class, IncomeNormalizer.class,
        YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class EligibilityQueryServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");

    @Autowired
    private EligibilityQueryService service;
    @Autowired
    private YouthPolicyNormalizationService normalizationService;
    @Autowired
    private RegionDirectory regionDirectory;
    @PersistenceContext
    private EntityManager em;

    private Region gangnam;
    private Region mapo;

    @BeforeEach
    void setUp() {
        Region seoul = Region.builder().regionCode("1100000000").regionName("서울특별시").build();
        gangnam = Region.builder().regionCode("1168000000").regionName("강남구").parent(seoul).build();
        mapo = Region.builder().regionCode("1144000000").regionName("마포구").parent(seoul).build();
        em.persist(seoul);
        em.persist(gangnam);
        em.persist(mapo);
        em.persist(Region.builder().regionCode("4100000000").regionName("경기도").build());
        em.flush();
        regionDirectory.refresh();
    }

    @Test
    void 정규화한_정책을_프로필과_비교해_판정과_신청_상태를_돌려준다() {
        Long opportunityId = normalizePolicy();
        persistProfile(1L, gangnam);

        EligibilityResponse response = service.evaluate(1L, opportunityId, NOW);

        assertThat(response.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(response.applicationStatus()).isEqualTo(ApplicationStatus.OPEN);
        assertThat(response.evaluatedAt()).isEqualTo(NOW);
        assertThat(response.conditions())
                .filteredOn(c -> c.result() != ConditionMatchResult.NOT_SPECIFIED)
                .extracting(EligibilityResponse.Condition::result)
                .containsOnly(ConditionMatchResult.MATCHED);
    }

    @Test
    void 정책_지역이_아닌_곳에_살면_NOT_ELIGIBLE() {
        Long opportunityId = normalizePolicy();
        persistProfile(2L, mapo);

        assertThat(service.evaluate(2L, opportunityId, NOW).eligibility()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
    }

    @Test
    void 판정_프로필이_없으면_판정하지_않는다() {
        Long opportunityId = normalizePolicy();

        assertThatThrownBy(() -> service.evaluate(99L, opportunityId, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EligibilityErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    void 목록은_정규화된_기회를_최신순으로_페이지_판정한다() {
        Long gangnamPolicy = normalizePolicy("R2026-1", "11680");
        Long mapoPolicy = normalizePolicy("R2026-2", "11440");
        persistProfile(3L, gangnam);

        EligibilityListResponse first = service.evaluateAll(3L, 0, 1, NOW);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.items()).singleElement().satisfies(item -> {
            assertThat(item.opportunityId()).isEqualTo(mapoPolicy);
            assertThat(item.eligibility()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
        });

        EligibilityListResponse second = service.evaluateAll(3L, 1, 1, NOW);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.items()).singleElement().satisfies(item -> {
            assertThat(item.opportunityId()).isEqualTo(gangnamPolicy);
            assertThat(item.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(item.applicationStatus()).isEqualTo(ApplicationStatus.OPEN);
        });
    }

    @Test
    void 목록_페이지_크기가_범위를_벗어나면_실패() {
        assertThatThrownBy(() -> service.evaluateAll(3L, 0, 101, NOW))
                .extracting("errorCode").isEqualTo(EligibilityErrorCode.INVALID_PAGE_REQUEST);
        assertThatThrownBy(() -> service.evaluateAll(3L, -1, 10, NOW))
                .extracting("errorCode").isEqualTo(EligibilityErrorCode.INVALID_PAGE_REQUEST);
    }

    private Long normalizePolicy() {
        return normalizePolicy("R2026-1", "11680");
    }

    private Long normalizePolicy(String plcyNo, String zipCd) {
        YouthPolicyRaw raw = YouthPolicyRaw.builder()
                .plcyNo(plcyNo).plcyNm("청년 월세 지원")
                .sprtTrgtMinAge("19").sprtTrgtMaxAge("34").sprtTrgtAgeLmtYn("Y")
                .aplyPrdSeCd("0057001").aplyYmd("20261001 ~ 20261031")
                .zipCd(zipCd)
                .build();
        NormalizationResult result = normalizationService.normalize(new NormalizationCommand(1L, raw, List.of(),
                "https://www.youthcenter.go.kr/policy/" + plcyNo, LocalDateTime.of(2026, 10, 14, 9, 0)));
        em.flush();
        em.clear();
        return result.opportunityId();
    }

    private void persistProfile(Long userId, Region residence) {
        em.persist(EligibilityProfile.builder()
                .userId(userId)
                .birthDate(LocalDate.of(1998, 5, 15))
                .residenceRegion(em.getReference(Region.class, residence.getRegionCode()))
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY)
                .enrollmentStatus(EnrollmentStatus.GRADUATED)
                .build());
        em.flush();
        em.clear();
    }
}
