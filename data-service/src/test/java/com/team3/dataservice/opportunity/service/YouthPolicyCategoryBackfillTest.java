package com.team3.dataservice.opportunity.service;

import com.team3.dataservice.opportunity.domain.entity.OpportunityCategory;
import com.team3.dataservice.opportunity.domain.repository.OpportunityCategoryRepository;
import com.team3.dataservice.opportunity.normalization.AgeNormalizer;
import com.team3.dataservice.opportunity.normalization.ApplicationPeriodNormalizer;
import com.team3.dataservice.opportunity.normalization.CodeConditionNormalizer;
import com.team3.dataservice.opportunity.normalization.IncomeNormalizer;
import com.team3.dataservice.opportunity.normalization.RegionNormalizer;
import com.team3.dataservice.opportunity.normalization.YouthPolicyCodeTable;
import com.team3.dataservice.opportunity.normalization.dto.NormalizationCommand;
import com.team3.dataservice.opportunity.normalization.dto.NormalizationResult;
import com.team3.dataservice.opportunity.normalization.dto.YouthPolicyRaw;
import com.team3.dataservice.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.dataservice.opportunity.normalization.official.OfficialSupplementer;
import com.team3.dataservice.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.dataservice.region.directory.RegionDirectory;
import com.team3.dataservice.support.ApplicationPostgresTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** 분류 테이블이 생기기 전 버전을 스냅샷으로 채우는지 실제 PostgreSQL에서 확인한다 */
@DataJpaTest
@Import({com.team3.dataservice.opportunity.conditionset.ConditionSetIdCache.class, OpportunitySourceLookup.class,
        YouthPolicyNormalizationService.class, YouthPolicyCategoryBackfill.class, AgeNormalizer.class,
        ApplicationPeriodNormalizer.class, RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class,
        IncomeNormalizer.class, YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class,
        PeriodEvidenceExtractor.class})
@Tag("postgres")
class YouthPolicyCategoryBackfillTest extends ApplicationPostgresTest {

    private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 10, 14, 9, 0);

    @Autowired
    private YouthPolicyNormalizationService service;
    @Autowired
    private YouthPolicyCategoryBackfill backfill;
    @Autowired
    private OpportunityCategoryRepository categoryRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @PersistenceContext
    private EntityManager em;

    @Test
    void 분류_행이_없는_버전만_스냅샷에서_채우고_다시_실행하면_아무것도_하지_않는다() {
        NormalizationResult multi = normalize("R-1", "일자리,교육", "취업,미래역량강화");
        NormalizationResult single = normalize("R-2", "주거", "주거지원");
        NormalizationResult empty = normalize("R-3", null, null);
        jdbcTemplate.update("delete from opportunity_categories");
        em.clear();

        assertThat(backfill.run()).isEqualTo(2);
        em.flush();
        em.clear();

        assertThat(categoryRepository.findByVersionIdOrderBySortOrderAsc(multi.versionId()))
                .extracting(OpportunityCategory::getSortOrder, OpportunityCategory::getLargeCategory, OpportunityCategory::getMediumCategory)
                .containsExactly(tuple((short) 0, "일자리", "취업"), tuple((short) 1, "교육", "미래역량강화"));
        assertThat(categoryRepository.findByVersionIdOrderBySortOrderAsc(single.versionId()))
                .extracting(OpportunityCategory::getMediumCategory).containsExactly("주거지원");
        assertThat(categoryRepository.findByVersionIdOrderBySortOrderAsc(empty.versionId())).isEmpty();

        assertThat(backfill.run()).isZero();
        em.flush();
        assertThat(categoryRepository.count()).isEqualTo(3);
    }

    private NormalizationResult normalize(String policyNo, String large, String medium) {
        YouthPolicyRaw raw = YouthPolicyRaw.builder()
                .plcyNo(policyNo).plcyNm("정책 " + policyNo)
                .sprtTrgtMinAge("19").sprtTrgtMaxAge("34").sprtTrgtAgeLmtYn("Y")
                .aplyPrdSeCd("0057001").aplyYmd("20261001 ~ 20261031")
                .lclsfNm(large).mclsfNm(medium)
                .build();
        NormalizationResult result = service.normalize(new NormalizationCommand(1L, raw, List.of(),
                "https://www.youthcenter.go.kr/policy/" + policyNo, DAY1));
        em.flush();
        return result;
    }
}
