package com.team3.backend.opportunity.domain.entity;

import org.junit.jupiter.api.Tag;

import com.team3.backend.region.domain.entity.Region;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 PostgreSQL에서 매핑/JSONB를 검증한다. 일회용 DB를 가리키는 YON_TEST_DB_URL이 있을 때만 실행된다.
 * 예) YON_TEST_DB_URL=jdbc:postgresql://localhost:5432/yon_test ./gradlew test
 * 주의: ddl-auto=create-drop 이므로 개발용 DB를 지정하지 말 것.
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
@Tag("postgres")
class OpportunityPersistenceTest {

    @PersistenceContext
    private EntityManager em;

    @Test
    void 버전_조건_신청기간_출처를_저장하고_JSONB를_조회한다() {
        Opportunity opportunity = persistOpportunity();
        OpportunityVersion v1 = persistVersion(opportunity, 1, Map.of("title", "청년 월세 지원"));
        opportunity.changeCurrentVersion(v1);

        Map<String, Object> expression = Map.of(
                "op", "AND",
                "children", List.of(
                        Map.of("kind", "RANGE", "min", 19, "max", 34),
                        Map.of("op", "NOT", "child", Map.of("field", "employment", "eq", "EMPLOYED"))
                )
        );
        em.persist(OpportunityCondition.builder()
                .version(v1)
                .conditionType(ConditionType.AGE)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("age")
                .expression(expression)
                .evidence(Map.of("rawText", "만 19세 ~ 34세"))
                .validationStatus(ValidationStatus.VALID)
                .build());
        em.persist(window(v1, PeriodType.FIXED, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));
        em.persist(ApplicationWindow.builder()
                .version(v1)
                .periodType(PeriodType.FIXED)
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .scope(Map.of("round", "2차", "regionCodes", List.of("11")))
                .validationStatus(ValidationStatus.VALID)
                .build());
        em.persist(window(v1, PeriodType.UNKNOWN, null, null));
        em.persist(OpportunitySource.builder()
                .opportunity(opportunity)
                .sourceId(1L)
                .externalId("R2026-0001")
                .sourceUrl("https://example.com/policy/R2026-0001")
                .firstSeenAt(LocalDateTime.now())
                .lastSeenAt(LocalDateTime.now())
                .build());
        em.flush();
        em.clear();

        Opportunity found = em.find(Opportunity.class, opportunity.getId());
        assertThat(found.getCurrentVersion().getId()).isEqualTo(v1.getId());

        OpportunityCondition condition = em.createQuery(
                        "select c from OpportunityCondition c where c.version.id = :vid", OpportunityCondition.class)
                .setParameter("vid", v1.getId())
                .getSingleResult();
        assertThat(condition.getExpression()).containsEntry("op", "AND");
        assertThat((List<?>) condition.getExpression().get("children")).hasSize(2);
        assertThat(condition.getEvidence()).containsEntry("rawText", "만 19세 ~ 34세");

        Object jsonbType = em.createNativeQuery(
                        "select jsonb_typeof(expression) from opportunity_conditions where id = :id")
                .setParameter("id", condition.getId())
                .getSingleResult();
        assertThat(jsonbType).isEqualTo("object");

        List<ApplicationWindow> windows = em.createQuery(
                        "select w from ApplicationWindow w where w.version.id = :vid order by w.startDate",
                        ApplicationWindow.class)
                .setParameter("vid", v1.getId())
                .getResultList();
        assertThat(windows).hasSize(3);
        assertThat(windows).anySatisfy(w -> {
            assertThat(w.getPeriodType()).isEqualTo(PeriodType.UNKNOWN);
            assertThat(w.getStartDate()).isNull();
        });
        assertThat(windows).anySatisfy(w -> assertThat(w.getScope()).containsEntry("round", "2차"));

        OpportunitySource source = em.createQuery(
                        "select s from OpportunitySource s where s.sourceId = 1 and s.externalId = 'R2026-0001'",
                        OpportunitySource.class)
                .getSingleResult();
        assertThat(source.getOpportunity().getId()).isEqualTo(opportunity.getId());
    }

    @Test
    void 새_버전을_현재_버전으로_지정해도_과거_버전은_보존된다() {
        Opportunity opportunity = persistOpportunity();
        OpportunityVersion v1 = persistVersion(opportunity, 1, Map.of("title", "v1"));
        opportunity.changeCurrentVersion(v1);
        em.flush();

        OpportunityVersion v2 = persistVersion(opportunity, 2, Map.of("title", "v2"));
        opportunity.changeCurrentVersion(v2);
        em.flush();
        em.clear();

        Opportunity found = em.find(Opportunity.class, opportunity.getId());
        assertThat(found.getCurrentVersion().getVersionNo()).isEqualTo(2);
        OpportunityVersion old = em.find(OpportunityVersion.class, v1.getId());
        assertThat(old.getNormalizedSnapshot()).containsEntry("title", "v1");
    }

    @Test
    void 키_순서가_달라도_같은_정규화_결과는_jsonb로_동일하게_비교된다() {
        Opportunity opportunity = persistOpportunity();
        persistVersion(opportunity, 1, Map.of("title", "v1", "age", Map.of("status", "RANGE", "min", 19, "max", 34)));
        em.flush();

        Object sameCount = em.createNativeQuery("""
                        select count(*) from opportunity_versions
                        where opportunity_id = :id and normalized_snapshot = cast(:snapshot as jsonb)
                        """)
                .setParameter("id", opportunity.getId())
                .setParameter("snapshot", "{\"age\": {\"max\": 34, \"min\": 19, \"status\": \"RANGE\"}, \"title\": \"v1\"}")
                .getSingleResult();
        assertThat(((Number) sameCount).longValue()).isEqualTo(1L);
    }

    @Test
    void 다른_Opportunity의_버전은_현재_버전으로_지정할_수_없다() {
        Opportunity a = persistOpportunity();
        Opportunity b = persistOpportunity();
        OpportunityVersion versionOfB = persistVersion(b, 1, Map.of());

        assertThatThrownBy(() -> a.changeCurrentVersion(versionOfB))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 버전별_적용_지역을_역할과_함께_저장한다() {
        Region seoul = Region.builder().regionCode("11").regionName("서울특별시").build();
        Region gangnam = Region.builder().regionCode("11680").regionName("강남구").parent(seoul).build();
        em.persist(seoul);
        em.persist(gangnam);

        Opportunity opportunity = persistOpportunity();
        OpportunityVersion v1 = persistVersion(opportunity, 1, Map.of());
        em.persist(OpportunityRegion.builder().version(v1).region(gangnam).regionRole(RegionRole.RESIDENCE).build());
        em.persist(OpportunityRegion.builder().version(v1).region(seoul).regionRole(RegionRole.SCHOOL).build());
        em.flush();
        em.clear();

        List<OpportunityRegion> regions = em.createQuery(
                        "select r from OpportunityRegion r join fetch r.region where r.version.id = :vid",
                        OpportunityRegion.class)
                .setParameter("vid", v1.getId())
                .getResultList();
        assertThat(regions).extracting(OpportunityRegion::getRegionRole)
                .containsExactlyInAnyOrder(RegionRole.RESIDENCE, RegionRole.SCHOOL);
        Region foundGangnam = em.find(Region.class, "11680");
        assertThat(foundGangnam.getParent().getRegionCode()).isEqualTo("11");
    }

    @Test
    void 훈련과정_회차_상세를_버전별로_저장하고_과정ID로_묶어_조회한다() {
        Opportunity round115 = persistOpportunity();
        OpportunityVersion v115 = persistVersion(round115, 1, Map.of("title", "굴삭기 면허취득과정"));
        Opportunity round116 = persistOpportunity();
        OpportunityVersion v116 = persistVersion(round116, 1, Map.of("title", "굴삭기 면허취득과정"));
        em.persist(trainingDetail(v115, 115, LocalDate.of(2026, 10, 1)));
        em.persist(trainingDetail(v116, 116, LocalDate.of(2026, 10, 15)));
        em.flush();
        em.clear();

        List<TrainingCourseDetail> rounds = em.createQuery(
                        "select d from TrainingCourseDetail d where d.courseId = :courseId order by d.courseRound",
                        TrainingCourseDetail.class)
                .setParameter("courseId", "AIG20250000502773")
                .getResultList();
        assertThat(rounds).extracting(TrainingCourseDetail::getCourseRound).containsExactly(115, 116);
        assertThat(rounds.getFirst().getVersion().getId()).isEqualTo(v115.getId());
        assertThat(rounds.getFirst().getTrainingStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(rounds.getFirst().getActualTrainingCost()).isEqualTo(330_000L);
    }

    @Test
    void 버전_하나에_훈련_상세는_하나만_저장된다() {
        Opportunity opportunity = persistOpportunity();
        OpportunityVersion v1 = persistVersion(opportunity, 1, Map.of("title", "v1"));
        em.persist(trainingDetail(v1, 115, LocalDate.of(2026, 10, 1)));
        em.flush();

        // IDENTITY 키라 persist 시점에 INSERT가 나가므로 persist부터 감싼다
        assertThatThrownBy(() -> {
            em.persist(trainingDetail(v1, 115, LocalDate.of(2026, 10, 1)));
            em.flush();
        }).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    private TrainingCourseDetail trainingDetail(OpportunityVersion version, int round, LocalDate start) {
        return TrainingCourseDetail.builder()
                .version(version)
                .courseId("AIG20250000502773")
                .courseRound(round)
                .institutionName("포천건설중장비운전학원")
                .institutionCode("202004027")
                .institutionCustomerId("500038675869")
                .trainingStartDate(start)
                .trainingEndDate(start.plusDays(1))
                .actualTrainingCost(330_000L)
                .courseFee(116_160L)
                .capacity(2)
                .ncsCode("14070106")
                .trainingTargetCode("C0061")
                .trainingTargetName("국민내일배움카드(일반)")
                .weekendTypeCode("3")
                .address("경기 포천시")
                .telNo("031-531-1002")
                .build();
    }

    private Opportunity persistOpportunity() {
        Opportunity opportunity = Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY)
                .title("청년 월세 지원")
                .build();
        em.persist(opportunity);
        return opportunity;
    }

    private OpportunityVersion persistVersion(Opportunity opportunity, int versionNo, Map<String, Object> snapshot) {
        OpportunityVersion version = OpportunityVersion.builder()
                .opportunity(opportunity)
                .versionNo(versionNo)
                .normalizedSnapshot(snapshot)
                .personalizationStatus(PersonalizationStatus.PERSONALIZABLE)
                .normalizationVersion("0.1.0")
                .build();
        em.persist(version);
        return version;
    }

    private ApplicationWindow window(OpportunityVersion version, PeriodType type, LocalDate start, LocalDate end) {
        return ApplicationWindow.builder()
                .version(version)
                .periodType(type)
                .startDate(start)
                .endDate(end)
                .periodText(start == null ? "추후 공지" : start + " ~ " + end)
                .validationStatus(ValidationStatus.VALID)
                .build();
    }
}
