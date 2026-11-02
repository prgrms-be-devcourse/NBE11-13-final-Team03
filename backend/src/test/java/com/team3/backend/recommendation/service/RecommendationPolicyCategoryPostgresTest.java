package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.domain.entity.EducationLevel;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.eligibility.support.EligibilityResultFixtures;
import com.team3.backend.fixture.BackendPostgres;
import com.team3.backend.fixture.PolicyCategoryRows;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.domain.entity.UserInterest;
import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityCategory;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청년정책 다중 분류의 추천 관심 적합도. 청년정책은 현재 버전의 분류 행 중 하나라도 중분류가 맞으면 HIGH, 대분류가 맞으면 MEDIUM이고,
 * 직업훈련은 세분류 HIGH·중분류 MEDIUM(공고 컬럼)이다. 분류 행이 없는 백필 전 정책은 관심 분야가 있으면 추천되지 않고 백필 후 추천된다.
 * 모든 경우에 운영 추천(DB 그룹별 상위 후보)과 전체 순회 참조 구현의 결과가 같아야 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {"collection.schedule.enabled=false", "app.scheduling.enabled=false"})
@Testcontainers
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("postgres")
class RecommendationPolicyCategoryPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = BackendPostgres.create();

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        BackendPostgres.register(registry, postgres);
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired private EntityManager entityManager;
    @Autowired private RecommendationSelectionService selectionService;
    @Autowired private EligibilityProfileService profileService;
    @Autowired private RecommendationCandidateService candidateService;
    @Autowired private RecommendationInterestMatcher interestMatcher;
    @Autowired private RecommendationEligibilityService eligibilityService;
    @Autowired private PreferenceService preferenceService;

    @Test
    void multiCategoryRowsDecideHighAndMediumAndPendingBackfillPolicyJoinsAfterBackfill() {
        Long policySet = EligibilityResultFixtures.newConditionSet(entityManager);
        Long trainingSet = EligibilityResultFixtures.newConditionSet(entityManager);
        // (일자리, 취업), (교육, 미래역량강화)
        Opportunity multi = policy("다중", policySet, "일자리,교육", "취업,미래역량강화",
                new String[]{"일자리", "취업"}, new String[]{"교육", "미래역량강화"});
        Opportunity single = policy("단일", policySet, "주거", "월세", new String[]{"주거", "월세"});
        Opportunity mediumOnly = policy("중분류만", policySet, "복지,문화", "미래역량강화",
                new String[]{"복지", null}, new String[]{"문화", null}, new String[]{null, "미래역량강화"});
        // 공고 컬럼에는 교육/미래역량강화가 있지만 분류 행이 없는 백필 전 정책
        Opportunity pending = policy("백필 전", policySet, "교육", "미래역량강화");
        Opportunity detailMatch = training("세분류", trainingSet, "정보통신", "정보기술");
        Opportunity subMatch = training("중분류", trainingSet, "정보통신", "통신기술");

        Long none = user(policySet, trainingSet);
        Long subAndDetail = user(policySet, trainingSet, InterestType.SUB_CATEGORY, "미래역량강화",
                InterestType.DETAIL_CATEGORY, "정보기술");
        Long categoryAndSub = user(policySet, trainingSet, InterestType.CATEGORY, "교육",
                InterestType.SUB_CATEGORY, "정보통신");
        // 추천은 관심 대·중분류를 짝으로 묶지 않는다 (기존 규칙): 교육·취업 관심이면 (일자리, 취업) 행으로 HIGH
        Long crossed = user(policySet, trainingSet, InterestType.CATEGORY, "교육", InterestType.SUB_CATEGORY, "취업");
        entityManager.flush();
        entityManager.clear();

        // 백필 전
        assertThat(levels(subAndDetail)).isEqualTo(Map.of(
                multi.getId(), InterestMatchLevel.HIGH, mediumOnly.getId(), InterestMatchLevel.HIGH,
                detailMatch.getId(), InterestMatchLevel.HIGH));
        assertThat(levels(categoryAndSub)).isEqualTo(Map.of(
                multi.getId(), InterestMatchLevel.MEDIUM,
                detailMatch.getId(), InterestMatchLevel.MEDIUM, subMatch.getId(), InterestMatchLevel.MEDIUM));
        assertThat(levels(crossed)).isEqualTo(Map.of(multi.getId(), InterestMatchLevel.HIGH));
        // 관심 미설정: 분류와 무관하게 UNRESTRICTED. ELIGIBLE 정책 4건이 모두 먼저 선정된다 (백필 전 정책 포함)
        Map<Long, InterestMatchLevel> unrestricted = levels(none);
        assertThat(unrestricted).containsKeys(multi.getId(), single.getId(), mediumOnly.getId(), pending.getId())
                .hasSize(5).allSatisfy((id, level) -> assertThat(level).isEqualTo(InterestMatchLevel.UNRESTRICTED));

        assertThat(PolicyCategoryRows.backfill(entityManager)).isEqualTo(1);
        entityManager.clear();

        // 백필 후: 백필 전 정책이 분류 행으로 매칭된다. 나머지는 그대로다
        assertThat(levels(subAndDetail)).isEqualTo(Map.of(
                multi.getId(), InterestMatchLevel.HIGH, mediumOnly.getId(), InterestMatchLevel.HIGH,
                pending.getId(), InterestMatchLevel.HIGH, detailMatch.getId(), InterestMatchLevel.HIGH));
        assertThat(levels(categoryAndSub)).isEqualTo(Map.of(
                multi.getId(), InterestMatchLevel.MEDIUM, pending.getId(), InterestMatchLevel.MEDIUM,
                detailMatch.getId(), InterestMatchLevel.MEDIUM, subMatch.getId(), InterestMatchLevel.MEDIUM));
        assertThat(levels(crossed)).isEqualTo(Map.of(
                multi.getId(), InterestMatchLevel.HIGH, pending.getId(), InterestMatchLevel.MEDIUM));
        assertThat(levels(none)).isEqualTo(unrestricted);
    }

    /**
     * 청년정책과 직업훈련이 같은 원본 대분류명 '교육･직업훈련'을 쓴다. 대분류 관심은 청년정책에만 쓰이므로(직업훈련은 중·세분류만 매칭)
     * 관심 대분류 '교육･직업훈련'은 그 분류 행이 있는 청년정책만 MEDIUM이고 직업훈련과는 맞지 않는다.
     */
    @Test
    void sameLargeCategoryNameMatchesOnlyYouthPoliciesForCategoryInterest() {
        Long policySet = EligibilityResultFixtures.newConditionSet(entityManager);
        Long trainingSet = EligibilityResultFixtures.newConditionSet(entityManager);
        Opportunity online = policy("온오프라인", policySet, "교육･직업훈련,교육･직업훈련", "온·오프라인교육 ,미래역량강화",
                new String[]{"교육･직업훈련", "온·오프라인교육"}, new String[]{"교육･직업훈련", "미래역량강화"});
        Opportunity education = policy("교육", policySet, "교육", "미래역량강화", new String[]{"교육", "미래역량강화"});
        Opportunity it = training("정보통신", trainingSet, "정보통신", "정보기술");

        Long largeOnly = user(policySet, trainingSet, InterestType.CATEGORY, "교육･직업훈련");
        Long policySub = user(policySet, trainingSet, InterestType.SUB_CATEGORY, "온·오프라인교육");
        Long sharedSub = user(policySet, trainingSet, InterestType.SUB_CATEGORY, "미래역량강화");
        Long trainingSub = user(policySet, trainingSet, InterestType.SUB_CATEGORY, "정보통신");
        entityManager.flush();
        entityManager.clear();

        assertThat(levels(largeOnly)).isEqualTo(Map.of(online.getId(), InterestMatchLevel.MEDIUM));
        assertThat(levels(policySub)).isEqualTo(Map.of(online.getId(), InterestMatchLevel.HIGH));
        // 중분류 관심은 대분류와 무관하게 같은 이름의 청년정책 중분류에 맞는다 (기존 규칙)
        assertThat(levels(sharedSub)).isEqualTo(Map.of(
                online.getId(), InterestMatchLevel.HIGH, education.getId(), InterestMatchLevel.HIGH));
        assertThat(levels(trainingSub)).isEqualTo(Map.of(it.getId(), InterestMatchLevel.MEDIUM));
    }

    /** 운영 추천 결과의 공고별 관심 적합도. 전체 순회 참조 구현과 결과 전체가 같은지도 확인한다 */
    private Map<Long, InterestMatchLevel> levels(Long userId) {
        List<RecommendationEligibleCandidate> actual = selectionService.select(userId, TODAY);
        List<RecommendationEligibleCandidate> expected = new RecommendationFullScanReference(profileService,
                candidateService, interestMatcher, eligibilityService, preferenceService).select(userId, 2, TODAY);
        assertThat(actual).as("운영 추천과 참조 구현").isEqualTo(expected);
        return actual.stream().collect(Collectors.toMap(item -> item.candidate().opportunityId(),
                RecommendationEligibleCandidate::interestMatchLevel));
    }

    private Opportunity policy(String title, Long conditionSet, String rawLarge, String rawMedium, String[]... rows) {
        Opportunity opportunity = opportunity(title, OpportunityType.YOUTH_POLICY, conditionSet);
        opportunity.updateSummary(title, rawLarge, rawMedium, null, null);
        entityManager.persist(ApplicationWindow.builder().version(opportunity.getCurrentVersion())
                .periodType(PeriodType.FIXED).startDate(TODAY.minusDays(1)).endDate(TODAY.plusDays(20))
                .validationStatus(ValidationStatus.VALID).build());
        short order = 0;
        for (String[] row : rows) {
            entityManager.persist(OpportunityCategory.builder().version(opportunity.getCurrentVersion())
                    .sortOrder(order++).largeCategory(row[0]).mediumCategory(row[1]).build());
        }
        return opportunity;
    }

    private Opportunity training(String title, Long conditionSet, String sub, String detail) {
        Opportunity opportunity = opportunity(title, OpportunityType.VOCATIONAL_TRAINING, conditionSet);
        opportunity.updateSummary(title, "교육･직업훈련", sub, detail, null);
        entityManager.persist(ApplicationWindow.builder().version(opportunity.getCurrentVersion())
                .periodType(PeriodType.UNKNOWN).validationStatus(ValidationStatus.UNKNOWN).build());
        entityManager.persist(TrainingCourseDetail.builder().version(opportunity.getCurrentVersion())
                .courseId("category-" + title).courseRound(1).trainingStartDate(TODAY.plusDays(10)).build());
        return opportunity;
    }

    private Opportunity opportunity(String title, OpportunityType type, Long conditionSet) {
        Opportunity opportunity = Opportunity.builder().title(title).opportunityType(type).build();
        entityManager.persist(opportunity);
        OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity).versionNo(1)
                .normalizedSnapshot(Map.of("title", title)).personalizationStatus(PersonalizationStatus.PERSONALIZABLE)
                .normalizationVersion("category").build();
        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
        entityManager.flush();
        entityManager.createNativeQuery("update opportunity_versions set condition_set_id = :set where id = :id")
                .setParameter("set", conditionSet).setParameter("id", version.getId()).executeUpdate();
        return opportunity;
    }

    /** 정책 묶음은 ELIGIBLE, 훈련 묶음은 POSSIBLY_ELIGIBLE인 사용자. interests는 (유형, 코드) 쌍의 나열 */
    private Long user(Long policySet, Long trainingSet, Object... interests) {
        Region region = entityManager.find(Region.class, "1100000000");
        if (region == null) {
            region = Region.builder().regionCode("1100000000").regionName("서울").build();
            entityManager.persist(region);
        }
        User account = User.builder().kakaoId(System.nanoTime() % 1_000_000_000L + 7_000_000_000L).role(Role.USER).build();
        entityManager.persist(account);
        entityManager.persist(EligibilityProfile.builder().userId(account.getId()).birthDate(LocalDate.of(1999, 3, 15))
                .residenceRegion(region).employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY).build());
        for (int i = 0; i < interests.length; i += 2) {
            entityManager.persist(UserInterest.create(account, (InterestType) interests[i], (String) interests[i + 1]));
        }
        EligibilityResultFixtures.result(entityManager, account.getId(), policySet, EligibilityStatus.ELIGIBLE,
                1L, EligibilityResultService.RULE_VERSION, TODAY.plusDays(30));
        EligibilityResultFixtures.result(entityManager, account.getId(), trainingSet, EligibilityStatus.POSSIBLY_ELIGIBLE,
                1L, EligibilityResultService.RULE_VERSION, TODAY.plusDays(30));
        return account.getId();
    }
}
