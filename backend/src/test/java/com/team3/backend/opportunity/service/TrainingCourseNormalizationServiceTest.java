package com.team3.backend.opportunity.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.dto.TrainingCourseRaw;
import com.team3.backend.opportunity.dto.TrainingNormalizationCommand;
import com.team3.backend.opportunity.dto.TrainingNormalizationResult;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
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
 * 고용24 훈련과정 페이지 정규화를 실제 PostgreSQL로 검증한다. 원본 값은 2026-10-16 응답 샘플 기준.
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
        assertThat(opportunity.getSubCategory()).isEqualTo("건설"); // NCS 14070106 → 대분류 14
        assertThat(opportunity.getDetailCategory()).isEqualTo("건설기계운전·정비"); // → 중분류 1407
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
        assertThat(detail.getTrainingTargetCode()).isEqualTo("C0061");

        assertThat(opportunityRegionRepository.findByVersionId(versionId)).singleElement().satisfies(r -> {
            assertThat(r.getRegion().getRegionCode()).isEqualTo("4165000000");
            assertThat(r.getRegionRole()).isEqualTo(RegionRole.IMPLEMENTATION);
        });
        assertThat(conditionRepository.findByVersionId(versionId)).singleElement().satisfies(c -> {
            assertThat(c.getConditionType()).isEqualTo(ConditionType.TRAINING_CARD);
            assertThat(c.getExpression()).containsEntry("status", "CARD_REQUIRED");
            assertThat(c.getValidationStatus()).isEqualTo(ValidationStatus.VALID);
        });
        assertThat(windowRepository.findByVersionId(versionId)).singleElement()
                .extracting(ApplicationWindow::getPeriodType).isEqualTo(PeriodType.UNKNOWN);
    }

    @Test
    void 분류는_NCS_대분류이고_코드가_없거나_표에_없으면_직업훈련이다() {
        TrainingCourseRaw it = TrainingCourseRaw.builder().trprId("NCS-IT").trprDegr("1").title("자바 백엔드 과정")
                .subTitle("훈련기관").traStartDate("2026-10-19").traEndDate("2027-03-30")
                .trainTarget("국민내일배움카드(일반)").trainTargetCd("C0061").trngAreaCd("41650")
                .ncsCd("20010202").build();
        TrainingCourseRaw noCode = withTarget("NCS-NONE", "국민내일배움카드(일반)", "C0061");
        TrainingCourseRaw unknownCode = TrainingCourseRaw.builder().trprId("NCS-99").trprDegr("1").title("미분류 과정")
                .subTitle("훈련기관").traStartDate("2026-10-19").traEndDate("2027-03-30")
                .trainTarget("국민내일배움카드(일반)").trainTargetCd("C0061").trngAreaCd("41650")
                .ncsCd("99010101").build();

        service.normalizePage(page(DAY1, it, noCode, unknownCode));
        flushAndClear();

        assertThat(opportunityOf("NCS-IT:1").getCategory()).isEqualTo("교육･직업훈련");
        assertThat(opportunityOf("NCS-IT:1").getSubCategory()).isEqualTo("정보통신");
        assertThat(opportunityOf("NCS-IT:1").getDetailCategory()).isEqualTo("정보기술");
        assertThat(opportunityOf("NCS-NONE:1").getSubCategory()).isEqualTo("직업훈련");
        assertThat(opportunityOf("NCS-NONE:1").getDetailCategory()).isNull();
        assertThat(opportunityOf("NCS-99:1").getSubCategory()).isEqualTo("직업훈련");
    }

    @Test
    void 원본이_같아도_분류는_다시_맞춘다() {
        service.normalizePage(page(DAY1, wallpaper()));
        flushAndClear();
        Opportunity before = opportunityOf("AIG20250000536091:8");
        Long versionId = before.getCurrentVersion().getId();
        em.createQuery("update Opportunity o set o.subCategory = '직업훈련' where o.id = :id")
                .setParameter("id", before.getId()).executeUpdate();
        em.clear();

        service.normalizePage(page(DAY1.plusDays(1), wallpaper()));
        flushAndClear();

        Opportunity after = opportunityOf("AIG20250000536091:8");
        assertThat(after.getSubCategory()).isEqualTo("건설"); // NCS 14030209 → 대분류 14
        assertThat(after.getDetailCategory()).isEqualTo("건축"); // → 중분류 1403
        assertThat(after.getCurrentVersion().getId()).isEqualTo(versionId);
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
    void 식별자나_제목이_없는_원본은_건너뛰고_나머지는_저장한다() {
        TrainingCourseRaw noId = TrainingCourseRaw.builder().trprDegr("1").title("과정").build();
        TrainingCourseRaw badRound = TrainingCourseRaw.builder().trprId("AIG1").trprDegr("일").title("과정").build();

        TrainingNormalizationResult result = service.normalizePage(page(DAY1, noId, excavator("2026-10-01", "2026-10-02"), badRound));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).extracting(TrainingNormalizationResult.Skipped::reason)
                .containsExactly("훈련과정ID(trprId) 없음", "회차(trprDegr)가 숫자가 아님: 일");
    }

    @Test
    void 형식이_잘못된_값은_비우고_활용_제한_사유로_남긴다() {
        TrainingCourseRaw raw = TrainingCourseRaw.builder()
                .trprId("AIG20250000502773").trprDegr("115").title("굴삭기 과정")
                .traStartDate("2026/10/01").realMan("33만원").trngAreaCd("41650")
                .build();

        service.normalizePage(page(DAY1, raw));
        flushAndClear();

        Opportunity opportunity = opportunityOf("AIG20250000502773:115");
        TrainingCourseDetail detail = detailRepository.findByVersionId(opportunity.getCurrentVersion().getId()).orElseThrow();
        assertThat(detail.getTrainingStartDate()).isNull();
        assertThat(detail.getActualTrainingCost()).isNull();
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) ((java.util.Map<String, Object>) opportunity.getCurrentVersion()
                .getNormalizedSnapshot().get("usage")).get("exclusionReasons");
        assertThat(reasons).contains("INVALID_traStartDate", "INVALID_realMan", "APPLICATION_PERIOD_UNCONFIRMED");
    }

    @Test
    void 훈련유형별로_자격_조건을_만든다() {
        service.normalizePage(page(DAY1, withTarget("A1", "K-디지털 트레이닝", "C0104"),
                withTarget("A2", "실업자원격훈련", "C0055"), withTarget("A3", "국민내일배움카드(주 훈련대상 : 구직자)", "C0061S")));
        flushAndClear();

        assertThat(conditionRepository.findByVersionId(opportunityOf("A1:1").getCurrentVersion().getId()))
                .extracting(c -> c.getConditionType() + ":" + c.getExpression().get("status") + ":" + c.getValidationStatus())
                .containsExactlyInAnyOrder("PARTICIPATION_HISTORY:ONCE_ONLY:VALID", "REGION:NATIONAL:VALID",
                        "TRAINING_CARD:UNSTRUCTURED:NEEDS_REVIEW");
        assertThat(conditionRepository.findByVersionId(opportunityOf("A2:1").getCurrentVersion().getId()))
                .extracting(c -> c.getConditionType() + ":" + c.getExpression().get("status") + ":" + c.getValidationStatus())
                .containsExactly("TRAINING_CARD:UNSTRUCTURED:NEEDS_REVIEW");
        assertThat(conditionRepository.findByVersionId(opportunityOf("A3:1").getCurrentVersion().getId()))
                .extracting(c -> c.getConditionType() + ":" + c.getExpression().get("status") + ":" + c.getValidationStatus())
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
    void 내일배움카드_과정은_카드를_발급받았으면_ELIGIBLE() {
        service.normalizePage(page(DAY1, excavator("2026-10-01", "2026-10-02")));
        flushAndClear();
        Long versionId = opportunityOf("AIG20250000502773:115").getCurrentVersion().getId();

        EligibilityEvaluator.Judgement judgement = evaluator.judge(
                profileBuilder().trainingCardStatus(TrainingCardStatus.ISSUED).build(), List.of(versionId), LocalDate.of(2026, 10, 16))
                .get(versionId);

        assertThat(judgement.evaluation().status()).isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test
    void 카드_발급_상태를_모르면_추가_입력_필요로_판정되고_신청_상태는_미확인이다() {
        service.normalizePage(page(DAY1, excavator("2026-10-01", "2026-10-02")));
        flushAndClear();
        Long versionId = opportunityOf("AIG20250000502773:115").getCurrentVersion().getId();
        EligibilityProfile profile = EligibilityProfile.builder()
                .userId(1L)
                .birthDate(LocalDate.of(1998, 5, 15))
                .residenceRegion(em.getReference(Region.class, pocheon.getRegionCode()))
                .build();

        EligibilityEvaluator.Judgement judgement = evaluator.judge(profile, List.of(versionId), LocalDate.of(2026, 10, 16))
                .get(versionId);

        assertThat(judgement.evaluation().status()).isEqualTo(EligibilityStatus.POSSIBLY_ELIGIBLE);
        assertThat(judgement.evaluation().conditions()).singleElement()
                .satisfies(c -> assertThat(c.result()).isEqualTo(ConditionMatchResult.USER_INFO_MISSING));
        assertThat(judgement.applicationStatus()).isEqualTo(ApplicationStatus.UNKNOWN);
    }

    @Test
    void 조건_묶음은_훈련유형으로만_나뉘고_훈련_지역과_무관하다() {
        service.normalizePage(page(DAY1,
                withTarget("C1", "국민내일배움카드(주 훈련대상 : 구직자)", "C0061S"),
                withTarget("C2", "국민내일배움카드(일반)", "C0061"),
                withTarget("K1", "K-디지털 트레이닝", "C0104"),
                withTarget("O1", "실업자원격훈련", "C0055"),
                withTarget("O2", "과정평가형", "C0055C"),
                TrainingCourseRaw.builder().trprId("C3").trprDegr("1").title("훈련과정 C3").subTitle("훈련기관")
                        .traStartDate("2026-10-19").traEndDate("2027-03-30")
                        .trainTarget("국민내일배움카드(일반)").trainTargetCd("C0061").trngAreaCd("41630").build()));
        flushAndClear();

        assertThat(setOf("C1:1")).isNotNull().isEqualTo(setOf("C2:1")).isEqualTo(setOf("C3:1"));
        assertThat(setOf("O1:1")).isNotNull().isEqualTo(setOf("O2:1"));
        assertThat(java.util.Set.of(setOf("C1:1"), setOf("K1:1"), setOf("O1:1"))).hasSize(3);
    }

    private Long setOf(String externalId) {
        return opportunityOf(externalId).getCurrentVersion().getConditionSetId();
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
                .trainTarget("국민내일배움카드(일반)").trainTargetCd("C0061").wkendSe("3")
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
                .trainTarget("국민내일배움카드(일반)").trainTargetCd("C0061").wkendSe("3")
                .trngAreaCd("41630").address("경기 양주시").telNo("031-855-7114")
                .titleLink("https://www.work24.go.kr/hr/a/a/3100/selectTracseDetl.do?tracseId=AIG20250000536091&tracseTme=8")
                .build();
    }
}
