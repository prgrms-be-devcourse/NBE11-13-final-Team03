package com.team3.backend.opportunity.conditionset;

import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.opportunity.normalization.dto.NormalizationCommand;
import com.team3.backend.opportunity.normalization.dto.NormalizationResult;
import com.team3.backend.opportunity.normalization.dto.TrainingCourseRaw;
import com.team3.backend.opportunity.normalization.dto.TrainingNormalizationCommand;
import com.team3.backend.opportunity.normalization.dto.YouthPolicyRaw;
import com.team3.backend.opportunity.normalization.AgeNormalizer;
import com.team3.backend.opportunity.normalization.ApplicationPeriodNormalizer;
import com.team3.backend.opportunity.normalization.CodeConditionNormalizer;
import com.team3.backend.opportunity.normalization.IncomeNormalizer;
import com.team3.backend.opportunity.normalization.RegionNormalizer;
import com.team3.backend.opportunity.normalization.YouthPolicyCodeTable;
import com.team3.backend.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.backend.opportunity.normalization.official.OfficialSupplementer;
import com.team3.backend.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.backend.opportunity.service.TrainingCourseNormalizationService;
import com.team3.backend.opportunity.service.YouthPolicyNormalizationService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건 묶음 백필이 정규화 때와 같은 조건 묶음을 채우는지 실제 PostgreSQL로 검증한다 (jsonb에서 읽은 조건으로 키 계산).
 * PostgreSQL 전용인 이유: 이 테스트의 핵심은 "메모리의 expression과 jsonb로 저장했다 읽은 expression이 같은 키를 내는가"인데,
 * 숫자 타입·키 순서 등 jsonb 왕복 결과는 H2와 다르다. 다른 정규화 테스트도 같은 이유로 YON_TEST_DB_URL로 실행한다.
 * 청크 실패 격리는 ConditionSetBackfillChunkTest(DB 없음, CI 포함)에서 검증한다.
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
@Import({ConditionSetBackfill.class, YouthPolicyNormalizationService.class, TrainingCourseNormalizationService.class,
        AgeNormalizer.class, ApplicationPeriodNormalizer.class, RegionNormalizer.class, RegionDirectory.class,
        CodeConditionNormalizer.class, IncomeNormalizer.class, YouthPolicyCodeTable.class,
        OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class ConditionSetBackfillTest {

    private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 10, 21, 9, 0);

    @Autowired
    private ConditionSetBackfill backfill;
    @Autowired
    private YouthPolicyNormalizationService youthService;
    @Autowired
    private TrainingCourseNormalizationService trainingService;
    @Autowired
    private OpportunityVersionRepository versionRepository;
    @Autowired
    private RegionDirectory regionDirectory;
    @Autowired
    private JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager em;

    @Test
    void 비어_있는_조건_묶음을_정규화_때와_같은_값으로_채운다() {
        Region seoul = Region.builder().regionCode("1100000000").regionName("서울특별시").build();
        em.persist(seoul);
        em.persist(Region.builder().regionCode("1168000000").regionName("강남구").parent(seoul).build());
        em.flush();
        regionDirectory.refresh();

        NormalizationResult policy = youthService.normalize(new NormalizationCommand(1L, YouthPolicyRaw.builder()
                .plcyNo("BF-1").plcyNm("백필 정책")
                .sprtTrgtMinAge("19").sprtTrgtMaxAge("34").sprtTrgtAgeLmtYn("Y")
                .aplyPrdSeCd("0057001").aplyYmd("20261001 ~ 20261031")
                .earnEtcCn("가구 기준중위소득 150% 이하").addAplyQlfcCndCn("무주택 세대주")
                .zipCd("11680").build(), List.of(), null, DAY1));
        trainingService.normalizePage(new TrainingNormalizationCommand(2L, List.of(TrainingCourseRaw.builder()
                .trprId("BF-T").trprDegr("1").title("백필 훈련").subTitle("기관")
                .traStartDate("2026-11-01").traEndDate("2027-03-30")
                .trainTarget("K-디지털 트레이닝").trainTargetCd("C0104").trngAreaCd("11680").build()), DAY1));
        em.flush();
        em.clear();
        regionDirectory.refresh();
        List<Long> before = conditionSets();

        jdbc.update("UPDATE opportunity_versions SET condition_set_id = NULL");
        assertThat(conditionSets()).containsOnlyNulls();

        assertThat(backfill.run()).isEqualTo(2);
        em.clear();

        assertThat(conditionSets()).isEqualTo(before).doesNotContainNull();
        assertThat(versionRepository.findById(policy.versionId()).orElseThrow().getConditionSetId())
                .isEqualTo(before.getFirst());
        assertThat(backfill.run()).isZero();
    }

    private List<Long> conditionSets() {
        return jdbc.queryForList("SELECT condition_set_id FROM opportunity_versions ORDER BY id", Long.class);
    }
}
