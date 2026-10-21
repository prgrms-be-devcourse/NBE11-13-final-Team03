package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultUpsertRepository;
import com.team3.backend.eligibility.engine.EligibilityEngine;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판정 결과 저장·재사용을 실제 PostgreSQL로 검증한다. 일회용 DB를 YON_TEST_DB_URL로 지정할 것 (ddl-auto=create-drop).
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
@Import({EligibilityResultService.class, EligibilityBatchRefresher.class, EligibilityEvaluator.class, EligibilityEngine.class, ApplicationStatusCalculator.class,
        YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class, IncomeNormalizer.class,
        YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class EligibilityResultServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
    private static final Long USER = 1L;

    @Autowired
    private EligibilityResultService service;
    @Autowired
    private EligibilityResultRepository resultRepository;
    @Autowired
    private EligibilityBatchRefresher batchRefresher;
    @Autowired
    private YouthPolicyNormalizationService normalizationService;
    @Autowired
    private RegionDirectory regionDirectory;
    @Autowired
    private JdbcTemplate jdbc;
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
        em.flush();
        regionDirectory.refresh();
    }

    @Test
    void 처음에는_전부_판정하고_다시_부르면_판정하지_않는다() {
        normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        persistProfile(gangnam);

        assertThat(service.refreshAll(USER, NOW)).isEqualTo(2);
        assertThat(service.refreshAll(USER, NOW)).isZero();
        assertThat(resultRepository.count()).isEqualTo(2);
    }

    @Test
    void 판정_입력이_바뀐_프로필은_다시_판정한다() {
        Long gangnamPolicy = normalizePolicy("R2026-1", "11680", "34");
        Long mapoPolicy = normalizePolicy("R2026-2", "11440", "34");
        persistProfile(gangnam);
        service.refreshAll(USER, NOW);

        em.createQuery("update EligibilityProfile p set p.residenceRegion = :region, "
                        + "p.profileVersion = p.profileVersion + 1 where p.userId = :userId")
                .setParameter("region", em.getReference(Region.class, mapo.getRegionCode()))
                .setParameter("userId", USER)
                .executeUpdate();
        em.clear();

        assertThat(service.refreshAll(USER, NOW)).isEqualTo(2);
        assertThat(statusOf(gangnamPolicy)).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
        assertThat(statusOf(mapoPolicy)).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(resultRepository.count()).isEqualTo(2);
    }

    @Test
    void 생일이_지나_만_나이가_바뀌면_다시_판정한다() {
        normalizePolicy("R2026-1", "11680", "34");
        persistProfile(gangnam);
        service.refreshAll(USER, NOW);

        assertThat(service.refreshAll(USER, OffsetDateTime.parse("2027-05-14T10:00:00+09:00"))).isZero();
        assertThat(service.refreshAll(USER, OffsetDateTime.parse("2027-05-15T10:00:00+09:00"))).isEqualTo(1);
        assertThat(resultRepository.findAll()).singleElement()
                .extracting(EligibilityResult::getValidUntil).isEqualTo(LocalDate.of(2028, 5, 15));
    }

    @Test
    void 새_정책_버전이_생긴_기회만_다시_판정한다() {
        Long changed = normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        persistProfile(gangnam);
        service.refreshAll(USER, NOW);

        normalizePolicy("R2026-1", "11680", "39");
        Long newVersionId = em.createQuery("select o.currentVersion.id from Opportunity o where o.id = :id", Long.class)
                .setParameter("id", changed).getSingleResult();

        assertThat(service.refreshAll(USER, NOW)).isEqualTo(1);
        assertThat(resultRepository.findByUserIdAndOpportunityIdIn(USER, List.of(changed))).singleElement()
                .extracting(EligibilityResult::getOpportunityVersionId).isEqualTo(newVersionId);
    }

    @Test
    void 판정_상태로_필터해_조회한다() {
        Long gangnamPolicy = normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        persistProfile(gangnam);
        service.refreshAll(USER, NOW);

        assertThat(resultRepository.findByUserIdAndEligibilityStatusIn(USER,
                        List.of(EligibilityStatus.ELIGIBLE, EligibilityStatus.POSSIBLY_ELIGIBLE), PageRequest.of(0, 10))
                .getContent())
                .singleElement().extracting(EligibilityResult::getOpportunityId).isEqualTo(gangnamPolicy);
    }

    @Test
    void 지정한_기회만_최신화하고_조건별_결과는_6가지_상태로_저장한다() {
        Long gangnamPolicy = normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        persistProfile(gangnam);

        List<EligibilityResult> results = service.refresh(USER, List.of(gangnamPolicy, 999_999L), NOW);

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.getEligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(r.getRuleVersion()).isEqualTo(EligibilityResultService.RULE_VERSION);
            assertThat(r.getProfileVersion()).isEqualTo(1L);
            assertThat(r.getConditionResults()).isNotEmpty();
            assertThat(r.getConditionResults()).extracting(m -> m.get("result"))
                    .allMatch(v -> Arrays.stream(ConditionMatchResult.values()).anyMatch(s -> s.name().equals(v)));
        });
        assertThat(resultRepository.count()).isEqualTo(1);

        em.clear();
        Map<String, Object> region = resultRepository.findAll().getFirst().getConditionResults().stream()
                .filter(m -> "REGION".equals(m.get("type"))).findFirst().orElseThrow();
        assertThat(region.get("result")).isEqualTo(ConditionMatchResult.MATCHED.name());
    }

    @Test
    void 규칙_버전이_바뀐_결과는_다시_판정해_같은_행을_갱신한다() {
        normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        persistProfile(gangnam);
        service.refreshAll(USER, NOW);
        List<Long> ids = jdbc.queryForList("SELECT id FROM eligibility_results ORDER BY opportunity_id", Long.class);

        jdbc.update("UPDATE eligibility_results SET rule_version = '0'");

        assertThat(service.refreshAll(USER, NOW)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id FROM eligibility_results ORDER BY opportunity_id", Long.class))
                .isEqualTo(ids);
        assertThat(jdbc.queryForList("SELECT DISTINCT rule_version FROM eligibility_results", String.class))
                .containsExactly(EligibilityResultService.RULE_VERSION);
    }

    @Test
    void 저장_묶음_크기와_상관없이_같은_결과를_저장한다() {
        normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        normalizePolicy("R2026-3", "11680", "39");
        persistProfile(gangnam);
        service.refreshAll(USER, NOW);
        List<Map<String, Object>> byDefault = dumpResults();

        jdbc.update("DELETE FROM eligibility_results");
        EligibilityResultService target = AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "writeBatchSize", 1);
        try {
            assertThat(service.refreshAll(USER, NOW)).isEqualTo(3);
        } finally {
            ReflectionTestUtils.setField(target, "writeBatchSize", 1_000);
        }
        assertThat(dumpResults()).isEqualTo(byDefault);
    }

    @Test
    void 같은_트랜잭션에서_다시_최신화하면_새_결과를_돌려준다() {
        Long gangnamPolicy = normalizePolicy("R2026-1", "11680", "34");
        persistProfile(gangnam);
        assertThat(service.refresh(USER, List.of(gangnamPolicy), NOW)).singleElement()
                .extracting(EligibilityResult::getEligibilityStatus).isEqualTo(EligibilityStatus.ELIGIBLE);

        em.createQuery("update EligibilityProfile p set p.residenceRegion = :region, "
                        + "p.profileVersion = p.profileVersion + 1 where p.userId = :userId")
                .setParameter("region", em.getReference(Region.class, mapo.getRegionCode()))
                .setParameter("userId", USER)
                .executeUpdate();
        em.clear();

        assertThat(service.refresh(USER, List.of(gangnamPolicy), NOW)).singleElement().satisfies(r -> {
            assertThat(r.getEligibilityStatus()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(r.getProfileVersion()).isEqualTo(2L);
        });
    }

    @Test
    void 오래된_결과만_고르는_최신화는_새_기회와_새_버전만_판정한다() {
        Long changed = normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        persistProfile(gangnam);
        assertThat(service.refreshStale(USER, NOW)).isEqualTo(2);
        assertThat(service.refreshStale(USER, NOW)).isZero();

        Long added = normalizePolicy("R2026-3", "11680", "34");
        normalizePolicy("R2026-1", "11680", "39");
        Long newVersionId = em.createQuery("select o.currentVersion.id from Opportunity o where o.id = :id", Long.class)
                .setParameter("id", changed).getSingleResult();

        assertThat(service.refreshStale(USER, NOW)).isEqualTo(2);
        assertThat(service.refreshStale(USER, NOW)).isZero();
        assertThat(resultRepository.count()).isEqualTo(3);
        assertThat(resultRepository.findByUserIdAndOpportunityIdIn(USER, List.of(changed))).singleElement()
                .extracting(EligibilityResult::getOpportunityVersionId).isEqualTo(newVersionId);
        assertThat(resultRepository.findByUserIdAndOpportunityIdIn(USER, List.of(added))).hasSize(1);
    }

    @Test
    void 오래된_결과만_고르는_최신화는_프로필_변경과_생일을_반영하고_프로필이_없으면_0() {
        assertThat(service.refreshStale(USER, NOW)).isZero();

        normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        persistProfile(gangnam);
        service.refreshStale(USER, NOW);

        assertThat(service.refreshStale(USER, OffsetDateTime.parse("2027-05-14T10:00:00+09:00"))).isZero();
        assertThat(service.refreshStale(USER, OffsetDateTime.parse("2027-05-15T10:00:00+09:00"))).isEqualTo(2);

        em.createQuery("update EligibilityProfile p set p.profileVersion = p.profileVersion + 1 where p.userId = :userId")
                .setParameter("userId", USER)
                .executeUpdate();
        em.clear();
        assertThat(service.refreshStale(USER, NOW)).isEqualTo(2);
    }

    @Test
    void 배치는_프로필이_있는_사용자마다_오래된_결과만_판정한다() {
        normalizePolicy("R2026-1", "11680", "34");
        normalizePolicy("R2026-2", "11440", "34");
        persistProfile(USER, gangnam);
        persistProfile(2L, mapo);

        assertThat(batchRefresher.refreshAllUsers(NOW)).isEqualTo(4);
        assertThat(batchRefresher.refreshAllUsers(NOW)).isZero();

        normalizePolicy("R2026-3", "11680", "34");
        assertThat(batchRefresher.refreshAllUsers(NOW)).isEqualTo(2);
        assertThat(resultRepository.count()).isEqualTo(6);
    }

    @Test
    void 늦게_끝난_옛_기회_버전_판정은_새_버전_결과를_덮어쓰지_않는다() {
        Long opportunityId = normalizePolicy("R2026-1", "11680", "34");
        Long oldVersionId = currentVersionId(opportunityId);
        persistProfile(gangnam);
        service.refreshStale(USER, NOW);

        normalizePolicy("R2026-1", "11680", "39");
        Long newVersionId = currentVersionId(opportunityId);
        assertThat(service.refreshStale(USER, NOW)).isEqualTo(1);

        int written = resultRepository.upsert(USER, 1L, EligibilityResultService.RULE_VERSION,
                LocalDateTime.of(2026, 10, 15, 10, 0), List.of(new EligibilityResultUpsertRepository.UpsertRow(
                        opportunityId, oldVersionId, EligibilityStatus.NOT_ELIGIBLE, "옛 버전 판정", List.of(), null)));
        em.clear();

        assertThat(written).isZero();
        assertThat(resultRepository.findByUserIdAndOpportunityIdIn(USER, List.of(opportunityId))).singleElement()
                .extracting(EligibilityResult::getOpportunityVersionId).isEqualTo(newVersionId);
    }

    @Test
    void 결과_테이블_통계가_없으면_분석해서_만든다() {
        normalizePolicy("R2026-1", "11680", "34");
        persistProfile(gangnam);
        service.refreshStale(USER, NOW);

        resultRepository.analyze();

        assertThat(resultRepository.lacksStatistics()).isFalse();
    }

    @Test
    void 다음_생일은_오늘이_생일이면_내년이다() {
        LocalDate birth = LocalDate.of(1998, 5, 15);
        assertThat(EligibilityResultService.nextBirthday(birth, LocalDate.of(2026, 5, 14))).isEqualTo(LocalDate.of(2026, 5, 15));
        assertThat(EligibilityResultService.nextBirthday(birth, LocalDate.of(2026, 5, 15))).isEqualTo(LocalDate.of(2027, 5, 15));
        assertThat(EligibilityResultService.nextBirthday(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 3, 1)))
                .isEqualTo(LocalDate.of(2027, 2, 28));
    }

    private List<Map<String, Object>> dumpResults() {
        return jdbc.queryForList("SELECT opportunity_id, opportunity_version_id, profile_version, rule_version, "
                + "eligibility_status, reason, condition_results::text AS c, valid_until, evaluated_at "
                + "FROM eligibility_results ORDER BY opportunity_id");
    }

    private EligibilityStatus statusOf(Long opportunityId) {
        return resultRepository.findByUserIdAndOpportunityIdIn(USER, List.of(opportunityId)).getFirst().getEligibilityStatus();
    }

    private Long currentVersionId(Long opportunityId) {
        return em.createQuery("select o.currentVersion.id from Opportunity o where o.id = :id", Long.class)
                .setParameter("id", opportunityId).getSingleResult();
    }

    private Long normalizePolicy(String plcyNo, String zipCd, String maxAge) {
        YouthPolicyRaw raw = YouthPolicyRaw.builder()
                .plcyNo(plcyNo).plcyNm("청년 월세 지원")
                .sprtTrgtMinAge("19").sprtTrgtMaxAge(maxAge).sprtTrgtAgeLmtYn("Y")
                .aplyPrdSeCd("0057001").aplyYmd("20261001 ~ 20261031")
                .zipCd(zipCd)
                .build();
        NormalizationResult result = normalizationService.normalize(new NormalizationCommand(1L, raw, List.of(),
                "https://www.youthcenter.go.kr/policy/" + plcyNo, LocalDateTime.of(2026, 10, 14, 9, 0)));
        em.flush();
        em.clear();
        return result.opportunityId();
    }

    private void persistProfile(Region residence) {
        persistProfile(USER, residence);
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
