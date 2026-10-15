package com.team3.backend.opportunity.service;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.dto.NormalizationCommand;
import com.team3.backend.opportunity.dto.NormalizationResult;
import com.team3.backend.opportunity.dto.YouthPolicyRaw;
import com.team3.backend.opportunity.normalization.AgeNormalizer;
import com.team3.backend.opportunity.normalization.ApplicationPeriodNormalizer;
import com.team3.backend.opportunity.normalization.CodeConditionNormalizer;
import com.team3.backend.opportunity.normalization.IncomeNormalizer;
import com.team3.backend.opportunity.normalization.RegionNormalizer;
import com.team3.backend.opportunity.normalization.YouthPolicyCodeTable;
import com.team3.backend.opportunity.normalization.official.*;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;

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
@Import({YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class,
        CodeConditionNormalizer.class, IncomeNormalizer.class, YouthPolicyCodeTable.class,
        OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class YouthPolicyNormalizationServiceTest {

    private static final long SOURCE_ID = 1L;
    private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 10, 14, 9, 0);
    private static final LocalDateTime DAY2 = LocalDateTime.of(2026, 10, 15, 9, 0);

    @Autowired
    private YouthPolicyNormalizationService service;
    @Autowired
    private OpportunityVersionRepository versionRepository;
    @Autowired
    private OpportunityConditionRepository conditionRepository;
    @Autowired
    private ApplicationWindowRepository windowRepository;
    @Autowired
    private OpportunitySourceRepository sourceRepository;
    @Autowired
    private OpportunityRegionRepository opportunityRegionRepository;
    @Autowired
    private RegionDirectory regionDirectory;
    @PersistenceContext
    private EntityManager em;

    @Test
    void 처음_들어온_정책은_Opportunity와_버전_조건_신청기간을_만든다() {
        NormalizationResult result = service.normalize(command(raw("19", "34", "20261001 ~ 20261031"), DAY1, List.of()));
        flushAndClear();

        assertThat(result.newOpportunity()).isTrue();
        assertThat(result.versionNo()).isEqualTo(1);

        OpportunityCondition age = condition(result.versionId(), ConditionType.AGE);
        assertThat(age.getConditionType()).isEqualTo(ConditionType.AGE);
        assertThat(age.getExpression()).containsEntry("status", "RANGE").containsEntry("min", 19).containsEntry("max", 34);
        assertThat(age.getEvidence()).containsEntry("sourceType", "YOUTH_CENTER_API");

        List<ApplicationWindow> windows = windowRepository.findByVersionId(result.versionId());
        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).getPeriodText()).isEqualTo("20261001 ~ 20261031");
    }

    @Test
    void 같은_원본이_다시_오면_DB에서_읽은_스냅샷과_비교해_새_버전을_만들지_않는다() {
        NormalizationResult first = service.normalize(command(raw("19", "34", "20261001 ~ 20261031"), DAY1, List.of()));
        flushAndClear();

        NormalizationResult second = service.normalize(command(raw("19", "34", "20261001 ~ 20261031"), DAY2, List.of()));
        flushAndClear();

        assertThat(second.newVersion()).isFalse();
        assertThat(second.versionId()).isEqualTo(first.versionId());
        assertThat(versionRepository.findByOpportunityIdOrderByVersionNoAsc(first.opportunityId())).hasSize(1);
        assertThat(sourceRepository.findBySourceIdAndExternalId(SOURCE_ID, "R2026-1").orElseThrow().getLastSeenAt())
                .isEqualTo(DAY2);
    }

    @Test
    void 원본_표기만_바뀌고_정규화_결과가_같으면_새_버전을_만들지_않는다() {
        service.normalize(command(raw("19", "34", "20261001 ~ 20261031"), DAY1, List.of()));
        flushAndClear();

        NormalizationResult result = service.normalize(command(raw("19", "34", "20261001~20261031"), DAY2, List.of()));

        assertThat(result.newVersion()).isFalse();
    }

    @Test
    void 정규화_결과가_바뀌면_새_버전을_만들고_과거_버전은_보존한다() {
        NormalizationResult v1 = service.normalize(command(raw("19", "34", "20261001 ~ 20261031"), DAY1, List.of()));
        flushAndClear();

        NormalizationResult v2 = service.normalize(command(raw("19", "39", "20261001 ~ 20261031"), DAY2, List.of()));
        flushAndClear();

        assertThat(v2.newVersion()).isTrue();
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(versionRepository.findByOpportunityIdOrderByVersionNoAsc(v1.opportunityId()))
                .extracting(OpportunityVersion::getVersionNo).containsExactly(1, 2);
        Opportunity opportunity = em.find(Opportunity.class, v1.opportunityId());
        assertThat(opportunity.getCurrentVersion().getId()).isEqualTo(v2.versionId());
        assertThat(condition(v1.versionId(), ConditionType.AGE).getExpression()).containsEntry("max", 34);
    }

    @Test
    void 영영Y를_공식_공고로_보완하고_근거를_저장한다() {
        OfficialDocument notice = new OfficialDocument(SourceType.OFFICIAL_NOTICE, "https://example.go.kr/notice/1",
                "2026년 청년 월세 지원\n지원대상: 만 19세 이상 34세 이하", "hash-1",
                OffsetDateTime.parse("2026-10-14T10:00:00+09:00"));

        NormalizationResult result = service.normalize(command(raw("0", "0", "20261001 ~ 20261031"), DAY1, List.of(notice)));
        flushAndClear();

        assertThat(result.ageOutcome()).isEqualTo(SupplementOutcome.FILLED);
        OpportunityCondition age = condition(result.versionId(), ConditionType.AGE);
        assertThat(age.getExpression()).containsEntry("min", 19);
        assertThat(age.getEvidence())
                .containsEntry("sourceType", "OFFICIAL_NOTICE")
                .containsEntry("sourceHash", "hash-1")
                .containsEntry("dataConflict", true);
        assertThat(age.getEvidence().get("raw")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("sprtTrgtMinAge", "0");
        assertThat(em.find(OpportunityVersion.class, result.versionId()).getPersonalizationStatus())
                .isEqualTo(PersonalizationStatus.PERSONALIZABLE);
    }

    @Test
    void zipCd를_지역_사전으로_매핑해_거주지_조건과_지역_행을_저장한다() {
        Region seoul = Region.builder().regionCode("1100000000").regionName("서울특별시").build();
        Region gyeonggi = Region.builder().regionCode("4100000000").regionName("경기도").build();
        em.persist(seoul);
        em.persist(gyeonggi);
        em.persist(Region.builder().regionCode("1111000000").regionName("종로구").parent(seoul).build());
        em.persist(Region.builder().regionCode("1144000000").regionName("마포구").parent(seoul).build());
        em.persist(Region.builder().regionCode("1168000000").regionName("강남구").parent(seoul).build());
        em.flush();
        regionDirectory.refresh();

        NormalizationResult result = service.normalize(command(
                raw("19", "34", "20261001 ~ 20261031", "11680,11440,29110"), DAY1, List.of()));
        flushAndClear();

        assertThat(opportunityRegionRepository.findByVersionId(result.versionId()))
                .extracting(r -> r.getRegion().getRegionCode())
                .containsExactlyInAnyOrder("1144000000", "1168000000");
        OpportunityCondition region = conditionRepository.findByVersionId(result.versionId()).stream()
                .filter(c -> c.getConditionType() == ConditionType.REGION).findFirst().orElseThrow();
        assertThat(region.getExpression()).containsEntry("status", "SPECIFIC").containsEntry("role", "RESIDENCE");
        assertThat(region.getValidationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW); // 29110: 통합 전 옛 코드
        regionDirectory.refresh();
    }

    @Test
    void 소득과_자유텍스트_조건을_저장하고_빈_참여제한은_행을_만들지_않는다() {
        YouthPolicyRaw raw = YouthPolicyRaw.builder()
                .plcyNo("R2026-1").plcyNm("청년 월세 지원")
                .sprtTrgtMinAge("19").sprtTrgtMaxAge("34").sprtTrgtAgeLmtYn("Y")
                .aplyPrdSeCd("0057001").aplyYmd("20261001 ~ 20261031")
                .earnEtcCn("가구 기준중위소득 150% 이하")
                .addAplyQlfcCndCn("무주택 세대주")
                .ptcpPrpTrgtCn("")
                .build();

        NormalizationResult result = service.normalize(command(raw, DAY1, List.of()));
        flushAndClear();

        OpportunityCondition income = condition(result.versionId(), ConditionType.INCOME);
        assertThat(income.getExpression())
                .containsEntry("type", "MEDIAN_PERCENT").containsEntry("max", 150)
                .containsEntry("maxInclusive", true).containsEntry("subject", "HOUSEHOLD");
        assertThat(income.getValidationStatus()).isEqualTo(ValidationStatus.VALID);

        List<OpportunityCondition> others = conditionRepository.findByVersionId(result.versionId()).stream()
                .filter(c -> c.getConditionType() == ConditionType.OTHER).toList();
        assertThat(others).hasSize(1);
        assertThat(others.get(0).getRequirementType()).isEqualTo(RequirementType.REQUIRED);
        assertThat(others.get(0).getExpression()).containsEntry("status", "UNSTRUCTURED");

        // 다시 읽은 스냅샷(소득 숫자 포함)과 비교해도 같은 결과면 새 버전을 만들지 않는다
        assertThat(service.normalize(command(raw, DAY2, List.of())).newVersion()).isFalse();
    }

    private OpportunityCondition condition(Long versionId, ConditionType type) {
        return conditionRepository.findByVersionId(versionId).stream()
                .filter(c -> c.getConditionType() == type).findFirst().orElseThrow();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private static NormalizationCommand command(YouthPolicyRaw raw, LocalDateTime seenAt, List<OfficialDocument> docs) {
        return new NormalizationCommand(SOURCE_ID, raw, docs, "https://www.youthcenter.go.kr/policy/R2026-1", seenAt);
    }

    private static YouthPolicyRaw raw(String min, String max, String aplyYmd) {
        return raw(min, max, aplyYmd, null);
    }

    private static YouthPolicyRaw raw(String min, String max, String aplyYmd, String zipCd) {
        return YouthPolicyRaw.builder()
                .plcyNo("R2026-1").plcyNm("청년 월세 지원")
                .sprtTrgtMinAge(min).sprtTrgtMaxAge(max).sprtTrgtAgeLmtYn("Y")
                .aplyPrdSeCd("0057001").aplyYmd(aplyYmd)
                .lclsfNm("주거").mclsfNm("주거지원").zipCd(zipCd)
                .build();
    }
}
