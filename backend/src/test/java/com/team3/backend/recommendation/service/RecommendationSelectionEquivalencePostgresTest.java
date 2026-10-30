package com.team3.backend.recommendation.service;

import org.junit.jupiter.api.Tag;

import com.team3.backend.eligibility.domain.entity.EducationLevel;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.eligibility.support.EligibilityResultFixtures;
import com.team3.backend.fixture.BackendPostgres;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.domain.entity.UserInterest;
import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 추천 선정(DB 그룹별 상위 후보 + 그룹 혼합)이 전체 후보 순회 참조 구현과 같은 결과를 내는지 무작위 소규모 Fixture(고정 시드)로 비교한다.
 * 결과는 후보 전체 필드(신청 상태·날짜 포함)·관심 적합도·신청 가능성까지 같아야 한다.
 * 분류·날짜 값의 범위를 좁게 잡아 같은 우선순위·같은 날짜(ID 정렬), B·C 혼합, D 그룹, 숨김, 미판정·만료·프로필·규칙 변경,
 * 다른 조건 묶음의 결과, 개인화 제외 상태, 정책·훈련 혼합이 생기게 하고, 그런 경우가 실제로 나왔는지도 확인한다.
 * 동일한 H2 실행을 중복하지 않고 PostgreSQL에서 모든 시드와 필드 비교를 유지한다.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {"collection.schedule.enabled=false", "app.scheduling.enabled=false"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("postgres")
class RecommendationSelectionEquivalencePostgresTest {
    @Container
    static final PostgreSQLContainer postgres = BackendPostgres.create();

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        BackendPostgres.register(registry, postgres);
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final int SEEDS = 40;
    private static final String[] CATEGORIES = {"c1", "c2", "c3", null};
    private static final String[] SUBS = {"s1", "s2", "s3", "s4", null};
    private static final String[] DETAILS = {"d1", "d2", "d3", null};
    /** 사용자별 관심 분야 (첫 사용자는 미설정 = UNRESTRICTED) */
    private static final List<List<Interest>> INTERESTS = List.of(
            List.of(),
            List.of(new Interest(InterestType.SUB_CATEGORY, "s1"), new Interest(InterestType.CATEGORY, "c1"),
                    new Interest(InterestType.DETAIL_CATEGORY, "d1")),
            List.of(new Interest(InterestType.SUB_CATEGORY, "s2"), new Interest(InterestType.SUB_CATEGORY, "s3"),
                    new Interest(InterestType.DETAIL_CATEGORY, "d2")),
            List.of(new Interest(InterestType.CATEGORY, "c2"), new Interest(InterestType.CATEGORY, "c3")),
            List.of(new Interest(InterestType.DETAIL_CATEGORY, "d1"), new Interest(InterestType.DETAIL_CATEGORY, "d3"),
                    new Interest(InterestType.SUB_CATEGORY, "s4")),
            List.of(new Interest(InterestType.SUB_CATEGORY, "s1"), new Interest(InterestType.CATEGORY, "c1"),
                    new Interest(InterestType.CATEGORY, "c2"), new Interest(InterestType.DETAIL_CATEGORY, "d2")));

    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RecommendationSelectionService selectionService;
    @Autowired private EligibilityProfileService profileService;
    @Autowired private RecommendationCandidateService candidateService;
    @Autowired private RecommendationInterestMatcher interestMatcher;
    @Autowired private RecommendationEligibilityService eligibilityService;
    @Autowired private PreferenceService preferenceService;

    @Test
    void topPerGroupSelectionMatchesFullScanReferenceOnRandomFixtures() {
        var reference = new RecommendationFullScanReference(profileService, candidateService, interestMatcher,
                eligibilityService, preferenceService);
        Map<String, Integer> coverage = new TreeMap<>();
        List<String> mismatches = new ArrayList<>();
        for (int seed = 1; seed <= SEEDS; seed++) {
            int currentSeed = seed;
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                List<Long> users = fixture(new Random(currentSeed));
                entityManager.flush();
                entityManager.clear();
                for (Long userId : users) {
                    // 배치 크기 7로 참조 구현이 여러 배치를 거치게 한다 (참조 결과는 배치 크기와 무관)
                    List<RecommendationEligibleCandidate> expected = reference.select(userId, 7, TODAY);
                    List<RecommendationEligibleCandidate> actual = selectionService.select(userId, TODAY);
                    if (!expected.equals(actual)) {
                        mismatches.add("seed=" + currentSeed + " user=" + userId
                                + "\n  expected=" + expected + "\n  actual  =" + actual);
                    }
                    record(coverage, expected);
                }
                status.setRollbackOnly();
            });
        }

        assertThat(mismatches).as("참조 구현과 다른 결과").isEmpty();
        // Fixture가 검증할 경우를 실제로 만들었는지 확인한다 (시드 40개 기준)
        assertThat(coverage).containsKeys("선정 0건", "선정 1건", "선정 5건", "관심 미설정", "B·C 혼합(C 1자리 확보)",
                "D 그룹 포함", "정책·훈련 혼합", "ELIGIBLE·POSSIBLY 혼합");
    }

    private static void record(Map<String, Integer> coverage, List<RecommendationEligibleCandidate> selected) {
        coverage.merge("선정 " + selected.size() + "건", 1, Integer::sum);
        if (selected.stream().anyMatch(item -> item.interestMatchLevel() == InterestMatchLevel.UNRESTRICTED)) {
            coverage.merge("관심 미설정", 1, Integer::sum);
        }
        long a = selected.stream().filter(item -> isGroup(item, EligibilityStatus.ELIGIBLE, InterestMatchLevel.HIGH)).count();
        boolean b = selected.stream().anyMatch(item -> isGroup(item, EligibilityStatus.ELIGIBLE, InterestMatchLevel.MEDIUM));
        boolean c = selected.stream().anyMatch(item -> isGroup(item, EligibilityStatus.POSSIBLY_ELIGIBLE, InterestMatchLevel.HIGH));
        if (a <= 3 && b && c) {
            coverage.merge("B·C 혼합(C 1자리 확보)", 1, Integer::sum);
        }
        if (selected.stream().anyMatch(item -> isGroup(item, EligibilityStatus.POSSIBLY_ELIGIBLE, InterestMatchLevel.MEDIUM))) {
            coverage.merge("D 그룹 포함", 1, Integer::sum);
        }
        if (selected.stream().map(item -> item.candidate().type()).distinct().count() > 1) {
            coverage.merge("정책·훈련 혼합", 1, Integer::sum);
        }
        if (selected.stream().map(RecommendationEligibleCandidate::eligibilityStatus).distinct().count() > 1) {
            coverage.merge("ELIGIBLE·POSSIBLY 혼합", 1, Integer::sum);
        }
    }

    private static boolean isGroup(RecommendationEligibleCandidate item, EligibilityStatus status, InterestMatchLevel level) {
        return item.eligibilityStatus() == status && item.interestMatchLevel() == level;
    }

    /** 공고 60건(신청기간·훈련 일정·개인화 상태·등록일·조건 묶음 무작위)과 사용자 6명의 판정·관심·숨김·저장 */
    private List<Long> fixture(Random random) {
        Region region = entityManager.find(Region.class, "1100000000");
        if (region == null) {
            region = Region.builder().regionCode("1100000000").regionName("서울").build();
            entityManager.persist(region);
        }
        List<Long> sets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sets.add(EligibilityResultFixtures.newConditionSet(entityManager));
        }
        List<Opportunity> opportunities = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            OpportunityType type = random.nextBoolean() ? OpportunityType.YOUTH_POLICY : OpportunityType.VOCATIONAL_TRAINING;
            Opportunity opportunity = Opportunity.builder().title("공고 " + i).opportunityType(type).build();
            entityManager.persist(opportunity);
            PersonalizationStatus personalization = random.nextInt(10) == 0
                    ? PersonalizationStatus.values()[random.nextInt(PersonalizationStatus.values().length)]
                    : (random.nextBoolean() ? PersonalizationStatus.PERSONALIZABLE : PersonalizationStatus.UNKNOWN);
            OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity).versionNo(1)
                    .normalizedSnapshot(Map.of("title", "공고 " + i)).personalizationStatus(personalization)
                    .normalizationVersion("equivalence").build();
            entityManager.persist(version);
            opportunity.changeCurrentVersion(version);
            opportunity.updateSummary("공고 " + i, pick(random, CATEGORIES), pick(random, SUBS), pick(random, DETAILS), null);
            if (type == OpportunityType.YOUTH_POLICY) {
                for (int w = 0, count = 1 + random.nextInt(2); w < count; w++) {
                    entityManager.persist(window(random, version));
                }
            } else {
                entityManager.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.UNKNOWN)
                        .validationStatus(ValidationStatus.UNKNOWN).build());
                if (random.nextInt(8) != 0) {
                    entityManager.persist(TrainingCourseDetail.builder().version(version).courseId("c-" + i).courseRound(1)
                            .trainingStartDate(TODAY.plusDays(3 + random.nextInt(24))).build());
                }
            }
            opportunities.add(opportunity);
        }
        entityManager.flush();
        for (Opportunity opportunity : opportunities) {
            entityManager.createNativeQuery("update opportunity_versions set condition_set_id = :set where id = :id")
                    .setParameter("set", sets.get(random.nextInt(sets.size())))
                    .setParameter("id", opportunity.getCurrentVersion().getId()).executeUpdate();
            entityManager.createNativeQuery("update opportunities set created_at = :created where id = :id")
                    .setParameter("created", TODAY.minusDays(random.nextInt(12)).atTime(random.nextInt(24), 0))
                    .setParameter("id", opportunity.getId()).executeUpdate();
        }

        List<Long> users = new ArrayList<>();
        for (List<Interest> interests : INTERESTS) {
            User account = User.builder().kakaoId(random.nextLong(1_000_000_000L, 9_000_000_000L)).role(Role.USER).build();
            entityManager.persist(account);
            entityManager.persist(EligibilityProfile.builder().userId(account.getId()).birthDate(LocalDate.of(1999, 3, 15))
                    .residenceRegion(region).employmentStatus(EmploymentStatus.UNEMPLOYED)
                    .educationLevel(EducationLevel.UNIVERSITY).build());
            for (Interest interest : interests) {
                entityManager.persist(UserInterest.create(account, interest.type, interest.code));
            }
            for (Long set : sets) {
                if (random.nextInt(6) == 0) {
                    continue; // 미판정 묶음
                }
                EligibilityStatus status = EligibilityStatus.values()[random.nextInt(EligibilityStatus.values().length)];
                int stale = random.nextInt(10);
                // 0: 프로필 버전 변경, 1: 규칙 변경, 2: 만료(validUntil=오늘)
                EligibilityResultFixtures.result(entityManager, account.getId(), set, status,
                        stale == 0 ? 2L : 1L, stale == 1 ? "old-rule" : EligibilityResultService.RULE_VERSION,
                        stale == 2 ? TODAY : TODAY.plusDays(30));
            }
            for (Opportunity opportunity : opportunities) {
                int choice = random.nextInt(10);
                if (choice < 2) {
                    UserOpportunityPreference preference = UserOpportunityPreference.builder().user(account)
                            .opportunity(opportunity).build();
                    if (choice == 0) {
                        preference.markHidden();
                    } else {
                        preference.markSaved(opportunity.getCurrentVersion().getId());
                    }
                    entityManager.persist(preference);
                }
            }
            users.add(account.getId());
        }
        return users;
    }

    private static ApplicationWindow window(Random random, OpportunityVersion version) {
        PeriodType type = switch (random.nextInt(10)) {
            case 0 -> PeriodType.ALWAYS;
            case 1 -> PeriodType.CLOSED;
            case 2 -> PeriodType.UNKNOWN;
            default -> PeriodType.FIXED;
        };
        ValidationStatus validation = random.nextInt(6) == 0 ? ValidationStatus.NEEDS_REVIEW : ValidationStatus.VALID;
        LocalDate start = random.nextInt(5) == 0 ? null : TODAY.plusDays(random.nextInt(16) - 10);
        LocalDate end = random.nextInt(6) == 0 ? null : TODAY.plusDays(random.nextInt(28) - 3);
        return ApplicationWindow.builder().version(version).periodType(type)
                .startDate(type == PeriodType.FIXED ? start : null).endDate(type == PeriodType.FIXED ? end : null)
                .validationStatus(validation).build();
    }

    private static String pick(Random random, String[] values) {
        return values[random.nextInt(values.length)];
    }

    private record Interest(InterestType type, String code) {
    }
}
