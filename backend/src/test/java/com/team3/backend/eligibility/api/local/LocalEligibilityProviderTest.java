package com.team3.backend.eligibility.api.local;

import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.api.dto.ConditionResult;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshot;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshotPage;
import com.team3.backend.eligibility.api.dto.OpportunityInfo;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.dto.NormalizationCommand;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 추천 등 다른 영역이 쓰는 판정 결과 제공 인터페이스의 로컬 구현을 실제 PostgreSQL로 검증한다.
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
@Import({LocalEligibilityProvider.class, OpportunityInfoAssembler.class, EligibilityResultService.class, EligibilityEvaluator.class,
        EligibilityEngine.class, ApplicationStatusCalculator.class,
        YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class, IncomeNormalizer.class,
        YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class LocalEligibilityProviderTest {

    private static final Long USER = 1L;

    @Autowired
    private EligibilityProvider provider;
    @Autowired
    private YouthPolicyNormalizationService normalizationService;
    @Autowired
    private RegionDirectory regionDirectory;
    @PersistenceContext
    private EntityManager em;

    private Region gangnam;

    @BeforeEach
    void setUp() {
        Region seoul = Region.builder().regionCode("1100000000").regionName("서울특별시").build();
        gangnam = Region.builder().regionCode("1168000000").regionName("강남구").parent(seoul).build();
        em.persist(seoul);
        em.persist(gangnam);
        em.persist(Region.builder().regionCode("1144000000").regionName("마포구").parent(seoul).build());
        em.flush();
        regionDirectory.refresh();
    }

    @Test
    void 기본_설정이면_로컬_구현이_주입된다() {
        assertThat(provider).isInstanceOf(LocalEligibilityProvider.class);
    }

    @Test
    void 후보_판정_결과를_요청한_순서대로_DTO로_돌려주고_정규화되지_않은_기회는_뺀다() {
        Long gangnamPolicy = normalizePolicy("R2026-1", "11680");
        Long mapoPolicy = normalizePolicy("R2026-2", "11440");
        persistProfile();

        List<EligibilitySnapshot> snapshots = provider.getEligibilities(USER, List.of(mapoPolicy, 999_999L, gangnamPolicy));

        assertThat(snapshots).extracting(EligibilitySnapshot::opportunityId).containsExactly(mapoPolicy, gangnamPolicy);
        assertThat(snapshots).extracting(EligibilitySnapshot::status)
                .containsExactly(EligibilityStatus.NOT_ELIGIBLE, EligibilityStatus.ELIGIBLE);

        EligibilitySnapshot eligible = snapshots.get(1);
        assertThat(eligible.userId()).isEqualTo(USER);
        assertThat(eligible.ruleVersion()).isEqualTo(EligibilityResultService.RULE_VERSION);
        assertThat(eligible.profileVersion()).isEqualTo(1L);
        assertThat(eligible.validUntil()).isNotNull();
        assertThat(eligible.conditions()).isNotEmpty()
                .filteredOn(c -> "REGION".equals(c.type()))
                .singleElement()
                .extracting(ConditionResult::result).isEqualTo(ConditionMatchResult.MATCHED);
    }

    @Test
    void 조건이_같은_기회는_판정_결과_하나를_함께_쓰고_기회별로_돌려준다() {
        Long first = normalizePolicy("R2026-1", "11680");
        Long second = normalizePolicy("R2026-3", "11680");
        persistProfile();

        List<EligibilitySnapshot> snapshots = provider.getEligibilities(USER, List.of(first, second));

        assertThat(snapshots).extracting(EligibilitySnapshot::opportunityId).containsExactly(first, second);
        assertThat(snapshots).extracting(EligibilitySnapshot::status).containsOnly(EligibilityStatus.ELIGIBLE);
        assertThat(snapshots.get(0).opportunityVersionId()).isNotEqualTo(snapshots.get(1).opportunityVersionId());
        assertThat(em.createQuery("select count(r) from EligibilityResult r", Long.class).getSingleResult())
                .isEqualTo(1L);
    }

    @Test
    void 판정_결과에_추천용_기회_요약을_함께_담는다() {
        Long policy = normalizePolicy("R2026-1", "11680");
        persistProfile();

        OpportunityInfo info = provider.getEligibilities(USER, List.of(policy)).getFirst().opportunity();

        assertThat(info.opportunityId()).isEqualTo(policy);
        assertThat(info.opportunityType()).isEqualTo(OpportunityType.YOUTH_POLICY);
        assertThat(info.title()).isEqualTo("청년 월세 지원");
        assertThat(info.category()).isEqualTo("주거");
        assertThat(info.subCategory()).isEqualTo("주거지원");
        assertThat(info.applicationStatus()).isEqualTo(ApplicationStatus.OPEN);
        assertThat(info.applicationEndDate()).isEqualTo(LocalDate.of(2099, 12, 31));
        assertThat(info.ncsCode()).isNull();
        assertThat(info.trainingStartDate()).isNull();
        assertThat(info.registeredAt()).isNotNull();
    }

    @Test
    void 판정_상태로_거른_페이지를_돌려준다() {
        Long gangnamPolicy = normalizePolicy("R2026-1", "11680");
        normalizePolicy("R2026-2", "11440");
        persistProfile();

        EligibilitySnapshotPage page = provider.findByStatuses(USER,
                List.of(EligibilityStatus.ELIGIBLE, EligibilityStatus.POSSIBLY_ELIGIBLE), 0, 10);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.items()).singleElement().extracting(EligibilitySnapshot::opportunityId).isEqualTo(gangnamPolicy);
    }

    @Test
    void 페이지_크기가_범위를_벗어나면_실패() {
        assertThatThrownBy(() -> provider.findByStatuses(USER, List.of(EligibilityStatus.ELIGIBLE), 0, 101))
                .extracting("errorCode").isEqualTo(EligibilityErrorCode.INVALID_PAGE_REQUEST);
    }

    @Test
    void 판정_프로필이_없으면_실패() {
        Long policy = normalizePolicy("R2026-1", "11680");

        assertThatThrownBy(() -> provider.getEligibilities(99L, List.of(policy)))
                .extracting("errorCode").isEqualTo(EligibilityErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    void 상태_일괄_조회는_재판정하지_않고_저장된_최신_결과만_돌려준다() {
        Long gangnamPolicy = normalizePolicy("R2026-1", "11680");
        Long mapoPolicy = normalizePolicy("R2026-2", "11440");
        persistProfile();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        assertThat(provider.findFreshStatuses(USER, List.of(gangnamPolicy, mapoPolicy), today)).isEmpty();
        assertThat(em.createQuery("select count(r) from EligibilityResult r", Long.class).getSingleResult()).isZero();

        provider.getEligibilities(USER, List.of(gangnamPolicy, mapoPolicy));

        assertThat(provider.findFreshStatuses(USER, List.of(gangnamPolicy, mapoPolicy, 999_999L), today))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        gangnamPolicy, EligibilityStatus.ELIGIBLE, mapoPolicy, EligibilityStatus.NOT_ELIGIBLE));

        em.createQuery("update EligibilityProfile p set p.profileVersion = p.profileVersion + 1 where p.userId = :u")
                .setParameter("u", USER).executeUpdate();
        em.clear();
        assertThat(provider.findFreshStatuses(USER, List.of(gangnamPolicy, mapoPolicy), today)).isEmpty();
    }

    @Test
    void 상태_일괄_조회는_프로필이_없거나_ID가_비면_빈_맵이다() {
        Long policy = normalizePolicy("R2026-1", "11680");

        assertThat(provider.findFreshStatuses(99L, List.of(policy), LocalDate.of(2026, 10, 20))).isEmpty();
        assertThat(provider.findFreshStatuses(USER, List.of(), LocalDate.of(2026, 10, 20))).isEmpty();
    }

    @Test
    void 상세_조회는_재판정하지_않고_저장된_최신_결과를_사유와_조건까지_돌려준다() {
        Long policy = normalizePolicy("R2026-1", "11680");
        persistProfile();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        assertThat(provider.findFreshEligibility(USER, policy, today)).isEmpty();
        assertThat(em.createQuery("select count(r) from EligibilityResult r", Long.class).getSingleResult()).isZero();

        EligibilitySnapshot judged = provider.getEligibilities(USER, List.of(policy)).getFirst();
        em.clear();

        EligibilitySnapshot fresh = provider.findFreshEligibility(USER, policy, today).orElseThrow();
        assertThat(fresh.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(fresh.reason()).isEqualTo(judged.reason());
        assertThat(fresh.conditions()).isEqualTo(judged.conditions()).isNotEmpty();
        assertThat(fresh.opportunity().opportunityId()).isEqualTo(policy);
        assertThat(fresh.opportunity().title()).isEqualTo("청년 월세 지원");
        assertThat(fresh.ruleVersion()).isEqualTo(EligibilityResultService.RULE_VERSION);

        em.createQuery("update EligibilityProfile p set p.profileVersion = p.profileVersion + 1 where p.userId = :u")
                .setParameter("u", USER).executeUpdate();
        em.clear();
        assertThat(provider.findFreshEligibility(USER, policy, today)).isEmpty();
    }

    @Test
    void 상세_조회는_프로필이_없거나_없는_기회면_빈_값이다() {
        Long policy = normalizePolicy("R2026-1", "11680");

        assertThat(provider.findFreshEligibility(99L, policy, LocalDate.of(2026, 10, 20))).isEmpty();
        assertThat(provider.findFreshEligibility(USER, 999_999L, LocalDate.of(2026, 10, 20))).isEmpty();
    }

    private Long normalizePolicy(String plcyNo, String zipCd) {
        YouthPolicyRaw raw = YouthPolicyRaw.builder()
                .plcyNo(plcyNo).plcyNm("청년 월세 지원")
                .sprtTrgtMinAge("19").sprtTrgtMaxAge("34").sprtTrgtAgeLmtYn("Y")
                .aplyPrdSeCd("0057001").aplyYmd("20260101 ~ 20991231")
                .lclsfNm("주거").mclsfNm("주거지원")
                .zipCd(zipCd)
                .build();
        Long id = normalizationService.normalize(new NormalizationCommand(1L, raw, List.of(),
                "https://www.youthcenter.go.kr/policy/" + plcyNo, LocalDateTime.of(2026, 10, 14, 9, 0))).opportunityId();
        em.flush();
        em.clear();
        return id;
    }

    private void persistProfile() {
        em.persist(EligibilityProfile.builder()
                .userId(USER)
                .birthDate(LocalDate.of(1998, 5, 15))
                .residenceRegion(em.getReference(Region.class, gangnam.getRegionCode()))
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY)
                .enrollmentStatus(EnrollmentStatus.GRADUATED)
                .build());
        em.flush();
        em.clear();
    }
}
