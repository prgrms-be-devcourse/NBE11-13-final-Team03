package com.team3.backend.eligibility.benchmark;

import com.team3.backend.eligibility.benchmark.BenchmarkData.Complexity;
import com.team3.backend.eligibility.benchmark.BenchmarkData.ProfileKind;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.dto.EligibilityListResponse;
import com.team3.backend.eligibility.dto.EligibilityResponse;
import com.team3.backend.eligibility.engine.EligibilityConditionInput;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.eligibility.service.EligibilityQueryService;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.normalization.*;
import com.team3.backend.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.backend.opportunity.normalization.official.OfficialSupplementer;
import com.team3.backend.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.backend.opportunity.service.YouthPolicyNormalizationService;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.repository.RegionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판정 조회를 Projection(필요 컬럼만 조회 → EligibilityConditionInput)으로 바꾼 뒤에도
 * 엔티티를 그대로 읽어 판정하던 기존 방식과 결과가 같은지 검증하는 회귀 테스트.
 * 템플릿 6종(단순·복합·불명확)을 섞은 정책 200건 × 판정 프로필 7종으로 목록(전 페이지)과 단건을 비교한다.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "YON_TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${YON_TEST_DB_URL}",
        "spring.datasource.username=${YON_TEST_DB_USERNAME:postgres}",
        "spring.datasource.password=${YON_TEST_DB_PASSWORD:1234}",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({EligibilityQueryService.class, EligibilityEngine.class, ApplicationStatusCalculator.class,
        YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class, IncomeNormalizer.class,
        YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class EligibilityProjectionEquivalenceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final int SIZE = 200;

    @Autowired private EligibilityQueryService queryService;
    @Autowired private EligibilityEngine engine;
    @Autowired private ApplicationStatusCalculator applicationStatusCalculator;
    @Autowired private YouthPolicyNormalizationService normalizationService;
    @Autowired private RegionDirectory regionDirectory;
    @Autowired private RegionRepository regionRepository;
    @Autowired private EligibilityProfileRepository profileRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private OpportunityConditionRepository conditionRepository;
    @Autowired private OpportunityRegionRepository opportunityRegionRepository;
    @Autowired private ApplicationWindowRepository windowRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    @Test
    void Projection_판정은_엔티티_기반_판정과_결과가_같다() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        BenchmarkData data = new BenchmarkData(jdbc, tx, regionRepository, regionDirectory, profileRepository, normalizationService);
        try {
            data.seedRegions();
            data.seedProfiles();
            var templates = data.normalizeTemplates();
            var ids = data.seedClones(templates, Complexity.MIXED, SIZE);
            data.verifyClones(templates, ids, SIZE);

            for (ProfileKind kind : ProfileKind.values()) {
                Map<Long, String[]> expected = entityBased(kind.userId());

                Map<Long, String> actual = new HashMap<>();
                int page = 0;
                EligibilityListResponse response;
                do {
                    response = queryService.evaluateAll(kind.userId(), page++, 100, NOW);
                    response.items().forEach(i -> actual.put(i.opportunityId(),
                            i.eligibility() + "|" + i.applicationStatus() + "|" + i.reason()));
                } while (response.hasNext());
                assertThat(actual).as("목록 판정 %s", kind)
                        .isEqualTo(expected.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue()[0])));

                // 단건 판정은 조건별 결과까지 비교 (템플릿 종류별로 하나씩)
                for (List<Long> group : ids.values()) {
                    Long opportunityId = group.get(0);
                    EligibilityResponse single = queryService.evaluate(kind.userId(), opportunityId, NOW);
                    String conditions = single.conditions().stream()
                            .map(c -> c.type() + "|" + c.field() + "|" + c.requirement() + "|" + c.result())
                            .sorted().collect(Collectors.joining(","));
                    assertThat(single.eligibility() + "|" + single.applicationStatus() + "|" + single.reason() + "|" + conditions)
                            .as("단건 판정 %s / 기회 %d", kind, opportunityId)
                            .isEqualTo(expected.get(opportunityId)[0] + "|" + expected.get(opportunityId)[1]);
                }
            }
        } finally {
            jdbc.execute("TRUNCATE eligibility_profile_special_targets, eligibility_profiles, opportunity_regions, "
                    + "application_windows, opportunity_conditions, opportunity_sources, opportunities, "
                    + "opportunity_versions, regions CASCADE");
        }
    }

    /** 기존 방식: 엔티티를 그대로 읽어 EligibilityConditionInput.from으로 변환해 판정 */
    /** 기회 ID → [최종|신청 상태|사유, 조건별 결과] */
    private Map<Long, String[]> entityBased(long userId) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(s -> {
            EligibilityProfile profile = profileRepository.findByUserId(userId).orElseThrow();
            Map<Long, String[]> result = new HashMap<>();
            for (Opportunity o : opportunityRepository.findAll()) {
                if (o.getCurrentVersion() == null) {
                    continue;
                }
                Long versionId = o.getCurrentVersion().getId();
                List<EligibilityConditionInput> conditions = conditionRepository.findByVersionId(versionId).stream()
                        .map(EligibilityConditionInput::from).toList();
                List<String> residence = opportunityRegionRepository.findByVersionId(versionId).stream()
                        .filter(r -> r.getRegionRole() == RegionRole.RESIDENCE)
                        .map(r -> r.getRegion().getRegionCode()).toList();
                List<ApplicationPeriod> periods = windowRepository.findByVersionId(versionId).stream()
                        .map(w -> new ApplicationPeriod(w.getPeriodType(), w.getStartDate(), w.getEndDate(),
                                w.getValidationStatus(), w.getPeriodText()))
                        .toList();
                EligibilityEvaluation evaluation = engine.evaluate(profile, conditions, residence, regionDirectory.index(), TODAY);
                ApplicationStatus app = applicationStatusCalculator.calculate(periods, TODAY);
                String conditionResults = evaluation.conditions().stream()
                        .map(c -> c.type() + "|" + c.field() + "|" + c.requirement() + "|" + c.result())
                        .sorted().collect(Collectors.joining(","));
                result.put(o.getId(), new String[]{evaluation.status() + "|" + app + "|" + evaluation.reason(), conditionResults});
            }
            return result;
        });
    }
}
