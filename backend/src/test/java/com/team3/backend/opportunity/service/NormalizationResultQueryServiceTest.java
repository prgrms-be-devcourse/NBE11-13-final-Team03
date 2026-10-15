package com.team3.backend.opportunity.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.dto.NormalizationResultResponse;
import com.team3.backend.opportunity.exception.OpportunityErrorCode;
import com.team3.backend.opportunity.normalization.*;
import com.team3.backend.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.backend.opportunity.normalization.official.OfficialSupplementer;
import com.team3.backend.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.backend.opportunity.region.RegionDirectory;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "YON_TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${YON_TEST_DB_URL}",
        "spring.datasource.username=${YON_TEST_DB_USERNAME:postgres}",
        "spring.datasource.password=${YON_TEST_DB_PASSWORD:1234}",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({NormalizationResultQueryService.class,
        YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class, IncomeNormalizer.class,
        YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class NormalizationResultQueryServiceTest {

    @Autowired
    private NormalizationResultQueryService service;
    @Autowired
    private YouthPolicyNormalizationService normalizationService;
    @Autowired
    private RegionDirectory regionDirectory;
    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void setUp() {
        Region seoul = Region.builder().regionCode("1100000000").regionName("서울특별시").build();
        em.persist(seoul);
        em.persist(Region.builder().regionCode("1168000000").regionName("강남구").parent(seoul).build());
        // 강남구만 있으면 서울 전체 → 전국으로 압축되므로 다른 지역도 둔다
        em.persist(Region.builder().regionCode("1144000000").regionName("마포구").parent(seoul).build());
        em.persist(Region.builder().regionCode("4100000000").regionName("경기도").build());
        em.flush();
        regionDirectory.refresh();
    }

    @Test
    void 현재_버전의_정규화_결과와_검토_필요_항목을_보여준다() {
        YouthPolicyRaw raw = YouthPolicyRaw.builder()
                .plcyNo("R2026-9").plcyNm("청년 월세 지원")
                .sprtTrgtMinAge("19").sprtTrgtMaxAge("34").sprtTrgtAgeLmtYn("Y")
                .aplyPrdSeCd("0057001").aplyYmd("20261001 ~ 20261031")
                .zipCd("11680")
                .addAplyQlfcCndCn("무주택 세대주일 것")
                .build();
        NormalizationResult result = normalizationService.normalize(new NormalizationCommand(1L, raw, List.of(),
                "https://www.youthcenter.go.kr/policy/R2026-9", LocalDateTime.of(2026, 10, 14, 9, 0)));
        em.flush();
        em.clear();

        NormalizationResultResponse response = service.get(result.opportunityId());

        assertThat(response.title()).isEqualTo("청년 월세 지원");
        assertThat(response.currentVersion().versionNo()).isEqualTo(1);
        assertThat(response.versions()).hasSize(1);
        assertThat(response.sources()).singleElement()
                .extracting(NormalizationResultResponse.Source::externalId).isEqualTo("R2026-9");
        assertThat(response.regions()).singleElement().satisfies(r -> {
            assertThat(r.regionCode()).isEqualTo("1168000000");
            assertThat(r.regionName()).isEqualTo("강남구");
        });
        assertThat(response.applicationWindows()).isNotEmpty();
        assertThat(response.conditions())
                .anySatisfy(c -> assertThat(c.validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW));
        assertThat(response.reviewSummary().needsReview()).isPositive();
    }

    @Test
    void 없는_기회는_실패() {
        assertThatThrownBy(() -> service.get(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OpportunityErrorCode.OPPORTUNITY_NOT_FOUND);
    }
}
