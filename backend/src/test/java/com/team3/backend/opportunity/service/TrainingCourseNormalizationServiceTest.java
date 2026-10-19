package com.team3.backend.opportunity.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.dto.TrainingCourseRaw;
import com.team3.backend.opportunity.dto.TrainingNormalizationCommand;
import com.team3.backend.opportunity.dto.TrainingNormalizationResult;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import com.team3.backend.opportunity.normalization.RegionNormalizer;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고용24 훈련과정 페이지 정규화를 실제 PostgreSQL로 검증한다. 원본 값은 2026-10-16 응답 샘플 기준
 * (정규화 대상이 KDT뿐이라 excavator·wallpaper는 훈련유형만 C0104로 바꿨다).
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
@Import({TrainingCourseNormalizationService.class, RegionNormalizer.class, RegionDirectory.class,
        EligibilityEvaluator.class, EligibilityEngine.class, ApplicationStatusCalculator.class})
class TrainingCourseNormalizationServiceTest {

    private static final Long SOURCE = 2L;
    private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 10, 16, 9, 0);
    private static final LocalDateTime DAY2 = LocalDateTime.of(2026, 10, 17, 9, 0);

    @Autowired
    private TrainingCourseNormalizationService service;
    @Autowired
    private RegionDirectory regionDirectory;
    @Autowired
    private OpportunitySourceRepository sourceRepository;
    @Autowired
    private TrainingCourseDetailRepository detailRepository;
    @Autowired
    private OpportunityRegionRepository opportunityRegionRepository;
    @Autowired
    private OpportunityConditionRepository conditionRepository;
    @Autowired
    private ApplicationWindowRepository windowRepository;
    @Autowired
    private EligibilityEvaluator evaluator;
    @PersistenceContext
    private EntityManager em;

    private Region pocheon;

    @BeforeEach
    void setUp() {
        Region gyeonggi = Region.builder().regionCode("4100000000").regionName("경기도").build();
        pocheon = Region.builder().regionCode("4165000000").regionName("포천시").parent(gyeonggi).build();
        em.persist(gyeonggi);
        em.persist(pocheon);
        em.persist(Region.builder().regionCode("4163000000").regionName("양주시").parent(gyeonggi).build());
        em.flush();
        regionDirectory.refresh();
    }

    @Test
    void 처음_들어온_회차는_기회_출처_버전_훈련상세_지역_조건_신청기간을_만든다() {
        TrainingNormalizationResult result = service.normalizePage(page(DAY1, excavator("2026-10-01", "2026-10-02"), wallpaper()));
        flushAndClear();

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).isEmpty();

        Opportunity opportunity = opportunityOf("AIG20250000502773:115");
        assertThat(opportunity.getOpportunityType()).isEqualTo(OpportunityType.VOCATIONAL_TRAINING);
        assertThat(opportunity.getTitle()).isEqualTo("소형(3톤미만) 굴삭기(굴착기) 면허취득과정");
        assertThat(opportunity.getCategory()).isEqualTo("교육･직업훈련");
        assertThat(opportunity.getSubCategory()).isEqualTo("직업훈련");
        assertThat(opportunity.getDetailUrl()).startsWith("https://www.work24.go.kr/");

        Long versionId = opportunity.getCurrentVersion().getId();
        TrainingCourseDetail detail = detailRepository.findByVersionId(versionId).orElseThrow();
        assertThat(detail.getCourseId()).isEqualTo("AIG20250000502773");
        assertThat(detail.getCourseRound()).isEqualTo(115);
        assertThat(detail.getInstitutionName()).isEqualTo("포천건설중장비운전학원");
        assertThat(detail.getTrainingStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(detail.getTrainingEndDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(detail.getActualTrainingCost()).isEqualTo(330_000L);
        assertThat(detail.getCourseFee()).isEqualTo(116_160L);
        assertThat(detail.getCapacity()).isEqualTo(2);
        assertThat(detail.getNcsCode()).isEqualTo("14070106");
        assertThat(detail.getTrainingTargetCode()).isEqualTo("C0104");

        assertThat(opportunityRegionRepository.findByVersionId(versionId)).singleElement().satisfies(r -> {
            assertThat(r.getRegion().getRegionCode()).isEqualTo("4165000000");
            assertThat(r.getRegionRole()).isEqualTo(RegionRole.IMPLEMENTATION);
        });
        assertThat(conditionRepository.findByVersionId(versionId)).hasSize(3);
        assertThat(windowRepository.findByVersionId(versionId)).singleElement().satisfies(w -> {
            assertThat(w.getPeriodType()).isEqualTo(PeriodType.FIXED);
            assertThat(w.getStartDate()).isNull();
            assertThat(w.getEndDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(w.getValidationStatus()).isEqualTo(ValidationStatus.VALID);
        });
        assertThat(opportunity.getCurrentVersion().getNormalizedSnapshot())
                .containsEntry("applicationPeriod", java.util.Map.of("endDate", "2026-09-30", "basis", "TRAINING_START_MINUS_ONE_DAY"));
        assertThat(usageReasons(opportunity)).contains("APPLICATION_PERIOD_ESTIMATED").doesNotContain("APPLICATION_PERIOD_UNCONFIRMED");
    }

    @Test
    void 같은_페이지가_다시_오면_새_버전을_만들지_않고_확인_시각만_갱신한다() {
        service.normalizePage(page(DAY1, excavator("2026-10-01", "2026-10-02"), wallpaper()));
        flushAndClear();

        TrainingNormalizationResult again = service.normalizePage(page(DAY2, excavator("2026-10-01", "2026-10-02"), wallpaper()));
        flushAndClear();

        assertThat(again.unchanged()).isEqualTo(2);
        assertThat(again.created()).isZero();
        assertThat(again.newVersions()).isZero();
        OpportunitySource source = sourceRepository.findBySourceIdAndExternalId(SOURCE, "AIG20250000502773:115").orElseThrow();
        assertThat(source.getLastSeenAt()).isEqualTo(DAY2);
        assertThat(source.getOpportunity().getCurrentVersion().getVersionNo()).isEqualTo(1);
    }

    @Test
    void 훈련_내용이_바뀌면_그_회차만_새_버전을_만든다() {
        service.normalizePage(page(DAY1, excavator("2026-10-01", "2026-10-02"), wallpaper()));
        flushAndClear();

        TrainingNormalizationResult changed = service.normalizePage(page(DAY2, excavator("2026-10-08", "2026-10-09"), wallpaper()));
        flushAndClear();

        assertThat(changed.newVersions()).isEqualTo(1);
        assertThat(changed.unchanged()).isEqualTo(1);
        Opportunity opportunity = opportunityOf("AIG20250000502773:115");
        assertThat(opportunity.getCurrentVersion().getVersionNo()).isEqualTo(2);
        assertThat(detailRepository.findByVersionId(opportunity.getCurrentVersion().getId()).orElseThrow()
                .getTrainingStartDate()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void 식별자나_제목이_없거나_KDT가_아닌_원본은_건너뛰고_나머지는_저장한다() {
        TrainingCourseRaw noId = TrainingCourseRaw.builder().trprDegr("1").title("과정").build();
        TrainingCourseRaw badRound = TrainingCourseRaw.builder().trprId("AIG1").trprDegr("일").title("과정").build();
        TrainingCourseRaw remote = withTarget("A2", "실업자원격훈련", "C0055");
        TrainingCourseRaw card = withTarget("A3", "국민내일배움카드(일반)", "C0061");
        TrainingCourseRaw noTarget = withTarget("A4", null, null);

        TrainingNormalizationResult result = service.normalizePage(
                page(DAY1, noId, excavator("2026-10-01", "2026-10-02"), badRound, remote, card, noTarget));
        flushAndClear();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).extracting(TrainingNormalizationResult.Skipped::reason)
                .containsExactly("훈련과정ID(trprId) 없음", "회차(trprDegr)가 숫자가 아님: 일",
                        "K-디지털 트레이닝(C0104)이 아님: C0055", "K-디지털 트레이닝(C0104)이 아님: C0061",
                        "K-디지털 트레이닝(C0104)이 아님: null");
        assertThat(sourceRepository.findBySourceIdAndExternalId(SOURCE, "A2:1")).isEmpty();
    }

    @Test
    void 형식이_잘못된_값은_비우고_활용_제한_사유로_남긴다() {
        TrainingCourseRaw raw = TrainingCourseRaw.builder()
                .trprId("AIG20250000502773").trprDegr("115").title("굴삭기 과정")
                .traStartDate("2026/10/01").realMan("33만원").trngAreaCd("41650")
                .trainTarget("K-디지털 트레이닝").trainTargetCd("C0104")
                .build();

        service.normalizePage(page(DAY1, raw));
        flushAndClear();

        Opportunity opportunity = opportunityOf("AIG20250000502773:115");
        TrainingCourseDetail detail = detailRepository.findByVersionId(opportunity.getCurrentVersion().getId()).orElseThrow();
        assertThat(detail.getTrainingStartDate()).isNull();
        assertThat(detail.getActualTrainingCost()).isNull();
        assertThat(usageReasons(opportunity)).contains("INVALID_traStartDate", "INVALID_realMan", "APPLICATION_PERIOD_UNCONFIRMED");
        assertThat(opportunity.getCurrentVersion().getNormalizedSnapshot()).doesNotContainKey("applicationPeriod");
        assertThat(windowRepository.findByVersionId(opportunity.getCurrentVersion().getId())).singleElement()
                .satisfies(w -> {
                    assertThat(w.getPeriodType()).isEqualTo(PeriodType.UNKNOWN);
                    assertThat(w.getValidationStatus()).isEqualTo(ValidationStatus.UNKNOWN);
                });
    }

    @Test
    void 개강일이_종료일보다_늦으면_신청기간을_추정하지_않는다() {
        service.normalizePage(page(DAY1, excavator("2026-10-09", "2026-10-02")));
        flushAndClear();

        Opportunity opportunity = opportunityOf("AIG20250000502773:115");
        assertThat(usageReasons(opportunity)).contains("INVALID_traStartDate>traEndDate", "APPLICATION_PERIOD_UNCONFIRMED");
        assertThat(windowRepository.findByVersionId(opportunity.getCurrentVersion().getId())).singleElement()
                .extracting(ApplicationWindow::getPeriodType).isEqualTo(PeriodType.UNKNOWN);
    }

    @Test
    void KDT_과정은_참여_이력_전국_원문확인_조건을_만든다() {
        service.normalizePage(page(DAY1, withTarget("A1", "K-디지털 트레이닝", "C0104")));
        flushAndClear();

        assertThat(conditionRepository.findByVersionId(opportunityOf("A1:1").getCurrentVersion().getId()))
                .extracting(c -> c.getConditionType() + ":" + c.getExpression().get("status") + ":" + c.getValidationStatus())
                .containsExactlyInAnyOrder("PARTICIPATION_HISTORY:ONCE_ONLY:VALID", "REGION:NATIONAL:VALID",
                        "TRAINING_CARD:UNSTRUCTURED:NEEDS_REVIEW");
    }

    @Test
    void 정규화_대상이_아닌_훈련유형의_자격_규칙은_그대로_남아_있다() {
        assertThat(TrainingCourseNormalizationService.trainingConditionSpecs(withTarget("A2", "실업자원격훈련", "C0055")))
                .extracting(c -> c.type() + ":" + c.expression().get("status") + ":" + c.validationStatus())
                .containsExactly("TRAINING_CARD:UNSTRUCTURED:NEEDS_REVIEW");
        assertThat(TrainingCourseNormalizationService.trainingConditionSpecs(
                withTarget("A3", "국민내일배움카드(주 훈련대상 : 구직자)", "C0061S")))
                .extracting(c -> c.type() + ":" + c.expression().get("status") + ":" + c.validationStatus())
                .containsExactly("TRAINING_CARD:CARD_REQUIRED:VALID");
    }

    @Test
    void KDT_과정은_참여_이력이_있으면_NOT_ELIGIBLE이고_없으면_원문_확인_필요() {
        service.normalizePage(page(DAY1, withTarget("A1", "K-디지털 트레이닝", "C0104")));
        flushAndClear();
        Long versionId = opportunityOf("A1:1").getCurrentVersion().getId();

        EligibilityProfile completed = profileBuilder().kdtParticipation(TrainingParticipation.COMPLETED).build();
        EligibilityProfile none = profileBuilder().kdtParticipation(TrainingParticipation.NONE).build();

        assertThat(evaluator.judge(completed, List.of(versionId), LocalDate.of(2026, 10, 16)).get(versionId)
                .evaluation().status()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
        assertThat(evaluator.judge(none, List.of(versionId), LocalDate.of(2026, 10, 16)).get(versionId)
                .evaluation().status()).isEqualTo(EligibilityStatus.POSSIBLY_ELIGIBLE);
        assertThat(evaluator.judge(none, List.of(versionId), LocalDate.of(2026, 10, 16)).get(versionId)
                .evaluation().conditions())
                .filteredOn(c -> c.type() == ConditionType.REGION).singleElement()
                .satisfies(c -> assertThat(c.result()).isEqualTo(ConditionMatchResult.MATCHED));
    }

    @Test
    void KDT_과정은_개강_전날까지_모집_중이고_개강일부터_마감이다() {
        service.normalizePage(page(DAY1, withTarget("A1", "K-디지털 트레이닝", "C0104")));
        flushAndClear();
        Long versionId = opportunityOf("A1:1").getCurrentVersion().getId();
        EligibilityProfile profile = profileBuilder().kdtParticipation(TrainingParticipation.NONE).build();

        assertThat(evaluator.judge(profile, List.of(versionId), LocalDate.of(2026, 10, 16)).get(versionId)
                .applicationStatus()).isEqualTo(ApplicationStatus.OPEN);
        assertThat(evaluator.judge(profile, List.of(versionId), LocalDate.of(2026, 10, 18)).get(versionId)
                .applicationStatus()).isEqualTo(ApplicationStatus.OPEN);
        assertThat(evaluator.judge(profile, List.of(versionId), LocalDate.of(2026, 10, 19)).get(versionId)
                .applicationStatus()).isEqualTo(ApplicationStatus.CLOSED);
    }

    private EligibilityProfile.EligibilityProfileBuilder profileBuilder() {
        return EligibilityProfile.builder()
                .userId(1L)
                .birthDate(LocalDate.of(1998, 5, 15))
                .residenceRegion(em.getReference(Region.class, pocheon.getRegionCode()));
    }

    private static TrainingCourseRaw withTarget(String trprId, String trainTarget, String trainTargetCd) {
        return TrainingCourseRaw.builder()
                .trprId(trprId).trprDegr("1")
                .title("훈련과정 " + trprId).subTitle("훈련기관")
                .traStartDate("2026-10-19").traEndDate("2027-03-30")
                .trainTarget(trainTarget).trainTargetCd(trainTargetCd)
                .trngAreaCd("41650")
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> usageReasons(Opportunity opportunity) {
        return (List<String>) ((java.util.Map<String, Object>) opportunity.getCurrentVersion()
                .getNormalizedSnapshot().get("usage")).get("exclusionReasons");
    }

    private Opportunity opportunityOf(String externalId) {
        return sourceRepository.findBySourceIdAndExternalId(SOURCE, externalId).orElseThrow().getOpportunity();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private static TrainingNormalizationCommand page(LocalDateTime seenAt, TrainingCourseRaw... courses) {
        return new TrainingNormalizationCommand(SOURCE, java.util.Arrays.asList(courses), seenAt);
    }

    private static TrainingCourseRaw excavator(String startDate, String endDate) {
        return TrainingCourseRaw.builder()
                .trprId("AIG20250000502773").trprDegr("115")
                .title("소형(3톤미만) 굴삭기(굴착기) 면허취득과정").subTitle("포천건설중장비운전학원")
                .instCd("202004027").trainstCstId("500038675869")
                .traStartDate(startDate).traEndDate(endDate)
                .realMan("330000").courseMan("116160").yardMan("2").ncsCd("14070106")
                .trainTarget("K-디지털 트레이닝").trainTargetCd("C0104").wkendSe("3")
                .trngAreaCd("41650").address("경기 포천시").telNo("031-531-1002")
                .titleLink("https://www.work24.go.kr/hr/a/a/3100/selectTracseDetl.do?tracseId=AIG20250000502773&tracseTme=115")
                .build();
    }

    private static TrainingCourseRaw wallpaper() {
        return TrainingCourseRaw.builder()
                .trprId("AIG20250000536091").trprDegr("8")
                .title("도배 입문 및 도배기능사 취득 과정").subTitle("경기북부건축인테리어기술학원")
                .instCd("202400765").trainstCstId("500044167056")
                .traStartDate("2026-10-01").traEndDate("2026-10-26")
                .realMan("733800").courseMan("613800").yardMan("16").ncsCd("14030209")
                .trainTarget("K-디지털 트레이닝").trainTargetCd("C0104").wkendSe("3")
                .trngAreaCd("41630").address("경기 양주시").telNo("031-855-7114")
                .titleLink("https://www.work24.go.kr/hr/a/a/3100/selectTracseDetl.do?tracseId=AIG20250000536091&tracseTme=8")
                .build();
    }
}
