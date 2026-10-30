package com.team3.backend.recommendation.controller;

import com.jayway.jsonpath.JsonPath;
import com.team3.backend.auth.security.CustomUserDetails;
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
import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.entity.RequirementType;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import com.team3.backend.recommendation.service.RecommendationCandidateService;
import com.team3.backend.recommendation.service.RecommendationEligibilityService;
import com.team3.backend.recommendation.service.RecommendationFullScanReference;
import com.team3.backend.recommendation.service.RecommendationInterestMatcher;
import com.team3.backend.recommendation.service.RecommendationSelectionService;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL 저장 데이터에서 추천 후보부터 인증된 HTTP 응답까지 연결한다. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "collection.schedule.enabled=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.team3.backend.recommendation.controller.RecommendationPostgresIntegrationTest$SqlCapture"
})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecommendationPostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Container
    static final PostgreSQLContainer postgres = BackendPostgres.create();

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        BackendPostgres.register(registry, postgres);
    }

    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private MockMvc mockMvc;
    @Autowired private RegionDirectory regionDirectory;
    @Autowired private RecommendationSelectionService selectionService;
    @Autowired private EligibilityProfileService profileService;
    @Autowired private RecommendationCandidateService candidateService;
    @Autowired private RecommendationInterestMatcher interestMatcher;
    @Autowired private RecommendationEligibilityService eligibilityService;
    @Autowired private PreferenceService preferenceService;

    @AfterEach
    void clearRegionIndex() {
        regionDirectory.refresh();
    }

    @Test
    void authenticatedRecommendationUsesDatabaseResultsPreferencesReasonsAndSameRegionsAsDetail() throws Exception {
        Region seoul = region("1100000000", "서울");
        Region busan = region("2600000000", "부산");
        Region oldRegion = region("2800000000", "인천");
        User owner = account(1001L);
        profile(owner, seoul);
        interest(owner, InterestType.SUB_CATEGORY, "취업");
        interest(owner, InterestType.CATEGORY, "주거");
        interest(owner, InterestType.DETAIL_CATEGORY, "정보기술");

        Opportunity high = policy("우선 정책", "일자리", "취업", 2);
        regionRow(high.getCurrentVersion(), oldRegion, RegionRole.RESIDENCE);
        high.changeCurrentVersion(newVersion(high, 2));
        window(high.getCurrentVersion(), 2);
        Opportunity medium = policy("관련 정책", "주거", "생활", 5);
        Opportunity training = training("직업훈련", "정보통신", "정보기술", 10);
        Opportunity possible = policy("확인 정책", "주거", "생활", 8);
        Opportunity hidden = policy("숨긴 정책", "일자리", "취업", 1);
        Opportunity mismatch = policy("무관한 정책", "복지", "건강", 1);
        Opportunity notEligible = policy("자격 불충족", "일자리", "취업", 1);
        regionCondition(high.getCurrentVersion(), "SPECIFIC", RegionRole.RESIDENCE, ValidationStatus.VALID);
        regionRow(high.getCurrentVersion(), seoul, RegionRole.RESIDENCE);
        regionRow(high.getCurrentVersion(), busan, RegionRole.RESIDENCE);
        regionCondition(medium.getCurrentVersion(), "NATIONAL", RegionRole.RESIDENCE, ValidationStatus.VALID);
        regionCondition(possible.getCurrentVersion(), "UNKNOWN", RegionRole.RESIDENCE, ValidationStatus.UNKNOWN);
        regionCondition(training.getCurrentVersion(), "NATIONAL", RegionRole.RESIDENCE, ValidationStatus.VALID);
        regionRow(training.getCurrentVersion(), seoul, RegionRole.IMPLEMENTATION);
        for (Opportunity opportunity : List.of(high, medium, hidden, mismatch)) {
            result(owner, opportunity, EligibilityStatus.ELIGIBLE, 1L,
                    EligibilityResultService.RULE_VERSION, TODAY.plusDays(20));
        }
        result(owner, training, EligibilityStatus.POSSIBLY_ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(20));
        result(owner, possible, EligibilityStatus.POSSIBLY_ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(20));
        result(owner, notEligible, EligibilityStatus.NOT_ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(20));
        preference(owner, high, true, false);
        preference(owner, hidden, true, true);
        flushAndClear();

        mockMvc.perform(get("/api/users/me/recommendations"))
                .andExpect(status().isUnauthorized());
        List<Map<String, Object>> items = recommendations(owner);
        assertEquals(List.of(high.getId(), medium.getId(), training.getId(), possible.getId()), ids(items));
        assertEquals(List.of("ELIGIBLE", "ELIGIBLE", "POSSIBLY_ELIGIBLE", "POSSIBLY_ELIGIBLE"),
                items.stream().map(item -> item.get("eligibilityStatus")).toList());
        assertEquals(true, items.getFirst().get("saved"));
        assertEquals(false, items.get(1).get("saved"));
        assertEquals(List.of("신청 조건 충족", "관심 분야 일치", "마감 D-2"), badges(items.getFirst()));
        assertEquals("교육 시작 D-10", badges(items.get(2)).getLast());
        assertTrue(String.valueOf(((Map<?, ?>) items.get(2).get("recommendationReasons")).get("description"))
                .contains("교육 시작"));
        assertEquals("RESIDENCE", regions(items.getFirst()).getFirst().get("role"));
        assertEquals(2, regions(items.getFirst()).size());
        assertFalse(regions(items.getFirst()).stream().anyMatch(region -> "2800000000".equals(region.get("code"))));
        assertEquals("NATIONAL", regions(items.get(1)).getFirst().get("status"));
        assertEquals("UNKNOWN", regions(items.get(3)).getFirst().get("status"));
        assertEquals("IMPLEMENTATION", regions(items.get(2)).getFirst().get("role"));
        assertEquals("UNVERIFIED", regions(items.get(2)).getFirst().get("validationStatus"));

        for (Map<String, Object> item : items) {
            MvcResult detail = mockMvc.perform(get("/api/opportunities/{id}", item.get("opportunityId")))
                    .andExpect(status().isOk()).andReturn();
            assertEquals(JsonPath.read(detail.getResponse().getContentAsString(), "$.regions"), item.get("regions"));
        }
    }

    @Test
    void staleResultsAndOtherUsersPreferencesNeverLeakIntoRecommendations() throws Exception {
        Region seoul = region("1100000000", "서울");
        User owner = account(2001L);
        User other = account(2002L);
        User noProfile = account(2003L);
        profile(owner, seoul);
        profile(other, seoul);
        interest(owner, InterestType.SUB_CATEGORY, "취업");
        interest(other, InterestType.SUB_CATEGORY, "다른 관심");
        Opportunity fresh = policy("정상 정책", "일자리", "취업", 3);
        Opportunity versionChanged = policy("버전 변경", "일자리", "취업", 3);
        Opportunity profileChanged = policy("프로필 변경", "일자리", "취업", 3);
        Opportunity ruleChanged = policy("규칙 변경", "일자리", "취업", 3);
        Opportunity expired = policy("만료", "일자리", "취업", 3);
        Opportunity unknown = policy("미확인 판정", "일자리", "취업", 3);
        Opportunity denied = policy("불가 판정", "일자리", "취업", 3);
        policy("미판정", "일자리", "취업", 3);
        result(owner, fresh, EligibilityStatus.ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
        result(owner, versionChanged, EligibilityStatus.ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
        versionChanged.changeCurrentVersion(newVersion(versionChanged, 2));
        window(versionChanged.getCurrentVersion(), 3);
        EligibilityResultFixtures.conditionSetOf(entityManager, versionChanged.getCurrentVersion());
        result(owner, profileChanged, EligibilityStatus.ELIGIBLE, 0L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
        result(owner, ruleChanged, EligibilityStatus.ELIGIBLE, 1L, "old-rule", TODAY.plusDays(2));
        result(owner, expired, EligibilityStatus.ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY);
        result(owner, unknown, EligibilityStatus.UNKNOWN, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
        result(owner, denied, EligibilityStatus.NOT_ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
        result(other, fresh, EligibilityStatus.POSSIBLY_ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
        preference(owner, fresh, true, true);
        flushAndClear();

        assertEquals(List.of(), ids(recommendations(owner)));
        assertEquals(List.of(), ids(recommendations(other)));
        mockMvc.perform(get("/api/users/me/recommendations")
                        .with(user(principal(noProfile))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY_001"));

        // 사용자 B의 관심 분야를 해제해도 A의 숨김·저장이 B에게 전파되지 않는다.
        entityManager.createQuery("delete from UserInterest i where i.user.id = :userId")
                .setParameter("userId", other.getId()).executeUpdate();
        entityManager.clear();
        List<Map<String, Object>> otherItems = recommendations(other);
        assertEquals(List.of(fresh.getId()), ids(otherItems));
        assertEquals("POSSIBLY_ELIGIBLE", otherItems.getFirst().get("eligibilityStatus"));
        assertEquals(false, otherItems.getFirst().get("saved"));
        assertEquals(List.of(), regions(otherItems.getFirst()));
    }

    @Test
    void laterCursorBatchCanReplaceEarlierChoicesAndBatchSizeDoesNotChangeSelection() {
        Region seoul = region("1100000000", "서울");
        User owner = account(3001L);
        profile(owner, seoul);
        for (int index = 0; index < 6; index++) {
            Opportunity early = policy("낮은 순위 " + index, "일자리", "취업", 25);
            result(owner, early, EligibilityStatus.ELIGIBLE, 1L,
                    EligibilityResultService.RULE_VERSION, TODAY.plusDays(30));
        }
        Opportunity late = policy("늦게 조회되는 마감 임박", "일자리", "취업", 1);
        result(owner, late, EligibilityStatus.ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(30));
        flushAndClear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        List<Long> smallBatch;
        List<Long> largeBatch;
        try {
            statistics.clear();
            smallBatch = fullScanReference().select(owner.getId(), 2, TODAY).stream()
                    .map(item -> item.candidate().opportunityId()).toList();
            long smallBatchStatements = statistics.getPrepareStatementCount();
            entityManager.clear();
            statistics.clear();
            largeBatch = fullScanReference().select(owner.getId(), 100, TODAY).stream()
                    .map(item -> item.candidate().opportunityId()).toList();
            long largeBatchStatements = statistics.getPrepareStatementCount();
            assertTrue(smallBatchStatements > largeBatchStatements);
            System.out.printf("RECOMMENDATION_BATCH_SQL size2=%d size100=%d%n",
                    smallBatchStatements, largeBatchStatements);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
        assertEquals(largeBatch, smallBatch);
        // 운영 경로(DB 그룹별 상위 후보)도 뒤 배치의 마감 임박 공고를 포함해 같은 결과를 낸다
        entityManager.clear();
        assertEquals(smallBatch, selectionService.select(owner.getId(), TODAY).stream()
                .map(item -> item.candidate().opportunityId()).toList());
        assertEquals(5, smallBatch.size());
        assertEquals(late.getId(), smallBatch.getFirst());
        assertEquals(5, smallBatch.stream().distinct().count());
    }

    @Test
    void regionRowAndConflictingStatusAreReturnedWithoutInventingPrecedence() throws Exception {
        Region seoul = region("1100000000", "서울");
        User owner = account(4001L);
        profile(owner, seoul);
        Opportunity policy = policy("상충 지역", "일자리", "취업", 2);
        regionRow(policy.getCurrentVersion(), seoul, RegionRole.RESIDENCE);
        regionCondition(policy.getCurrentVersion(), "NATIONAL", RegionRole.RESIDENCE, ValidationStatus.VALID);
        result(owner, policy, EligibilityStatus.ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
        flushAndClear();

        List<Map<String, Object>> items = recommendations(owner);
        assertEquals(1, items.size());
        assertEquals(List.of("SPECIFIC", "NATIONAL"), regions(items.getFirst()).stream()
                .map(region -> region.get("status")).toList());
        assertEquals("1100000000", regions(items.getFirst()).getFirst().get("code"));
        MvcResult detail = mockMvc.perform(get("/api/opportunities/{id}", policy.getId()))
                .andExpect(status().isOk()).andReturn();
        assertEquals(JsonPath.read(detail.getResponse().getContentAsString(), "$.regions"), items.getFirst().get("regions"));
    }

    @Test
    void selectedItemCountDoesNotIncreaseSqlStatementsWithinOneCandidateBatch() throws Exception {
        Region seoul = region("1100000000", "서울");
        User owner = account(5001L);
        profile(owner, seoul);
        Opportunity first = policy("첫 공고", "일자리", "취업", 1);
        regionRow(first.getCurrentVersion(), seoul, RegionRole.RESIDENCE);
        result(owner, first, EligibilityStatus.ELIGIBLE, 1L,
                EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
        flushAndClear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            SqlCapture.start();
            assertEquals(1, recommendations(owner).size());
            long coldStatements = statistics.getPrepareStatementCount();
            List<String> coldSql = SqlCapture.stop();
            assertEquals(coldStatements, coldSql.size());
            assertEquals(1, coldSql.stream().filter(sql -> sql.contains(" from application.regions ")).count());
            entityManager.clear();
            statistics.clear();
            SqlCapture.start();
            assertEquals(1, recommendations(owner).size());
            long oneItemStatements = statistics.getPrepareStatementCount();
            List<String> oneItemSql = SqlCapture.stop();
            assertEquals(oneItemStatements, oneItemSql.size());
            assertTrue(oneItemSql.stream().anyMatch(sql -> sql.contains("user_interests")));
            assertTrue(oneItemSql.stream().anyMatch(sql -> sql.contains("eligibility_set_results")));
            assertTrue(oneItemSql.stream().anyMatch(sql -> sql.contains("user_opportunity_preferences")));
            assertTrue(oneItemSql.stream().anyMatch(sql -> sql.contains("opportunity_regions")));
            assertTrue(oneItemSql.stream().anyMatch(sql -> sql.contains("opportunity_versions")));
            // 읽기 전용: SELECT 또는 쓰기가 없는 CTE 조회(그룹별 상위 후보)만 실행된다
            assertTrue(oneItemSql.stream().map(sql -> sql.stripLeading().toLowerCase()).allMatch(sql -> sql.startsWith("select")
                    || (sql.startsWith("with") && !sql.matches("(?s).*\\b(insert|update|delete|merge)\\b.*"))));
            assertEquals(oneItemStatements + 1, coldStatements);
            System.out.println("RECOMMENDATION_SQL_TABLES " + oneItemSql.stream()
                    .map(sql -> {
                        String compact = sql.replaceAll("\\s+", " ");
                        int from = compact.toLowerCase().indexOf(" from ");
                        return from < 0 ? compact : compact.substring(from, Math.min(compact.length(), from + 90));
                    }).toList());

            for (int index = 0; index < 4; index++) {
                Opportunity next = policy("추가 공고 " + index, "일자리", "취업", index + 2);
                regionRow(next.getCurrentVersion(), seoul, RegionRole.RESIDENCE);
                result(owner, next, EligibilityStatus.ELIGIBLE, 1L,
                        EligibilityResultService.RULE_VERSION, TODAY.plusDays(2));
            }
            flushAndClear();
            regionDirectory.index();
            statistics.clear();
            SqlCapture.start();
            assertEquals(5, recommendations(owner).size());
            long fiveItemStatements = statistics.getPrepareStatementCount();
            List<String> fiveItemSql = SqlCapture.stop();
            assertEquals(fiveItemStatements, fiveItemSql.size());
            assertEquals(oneItemStatements, fiveItemStatements);
            System.out.printf("RECOMMENDATION_SQL cold=%d warmOne=%d warmFive=%d%n",
                    coldStatements, oneItemStatements, fiveItemStatements);
        } finally {
            SqlCapture.stop();
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
    }

    /** 운영 경로 이전의 전체 후보 순회 참조 구현 (배치 크기와 무관한 결과·SQL 수 비교용) */
    private RecommendationFullScanReference fullScanReference() {
        return new RecommendationFullScanReference(profileService, candidateService, interestMatcher,
                eligibilityService, preferenceService);
    }

    private List<Map<String, Object>> recommendations(User owner) throws Exception {
        MvcResult response = mockMvc.perform(get("/api/users/me/recommendations")
                        .with(user(principal(owner))))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(response.getResponse().getContentAsString(), "$");
    }

    private static List<Long> ids(List<Map<String, Object>> items) {
        return items.stream().map(item -> ((Number) item.get("opportunityId")).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> regions(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("regions");
    }

    @SuppressWarnings("unchecked")
    private static List<String> badges(Map<String, Object> item) {
        return (List<String>) ((Map<String, Object>) item.get("recommendationReasons")).get("badges");
    }

    private static CustomUserDetails principal(User owner) {
        return new CustomUserDetails(owner.getId(), Role.USER, "recommendation-test");
    }

    private Region region(String code, String name) {
        Region value = Region.builder().regionCode(code).regionName(name).build();
        entityManager.persist(value);
        return value;
    }

    private User account(Long kakaoId) {
        User account = User.builder().kakaoId(kakaoId).role(Role.USER).build();
        entityManager.persist(account);
        return account;
    }

    private void profile(User owner, Region residence) {
        entityManager.persist(EligibilityProfile.builder().userId(owner.getId())
                .birthDate(LocalDate.of(1998, 1, 1)).residenceRegion(residence)
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY).build());
    }

    private void interest(User owner, InterestType type, String code) {
        entityManager.persist(UserInterest.create(owner, type, code));
    }

    private Opportunity policy(String title, String category, String subCategory, int deadlineDays) {
        Opportunity opportunity = opportunity(title, OpportunityType.YOUTH_POLICY, category, subCategory, null);
        window(opportunity.getCurrentVersion(), deadlineDays);
        return opportunity;
    }

    private Opportunity training(String title, String subCategory, String detailCategory, int startDays) {
        Opportunity opportunity = opportunity(title, OpportunityType.VOCATIONAL_TRAINING,
                "교육･직업훈련", subCategory, detailCategory);
        OpportunityVersion version = opportunity.getCurrentVersion();
        entityManager.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.UNKNOWN)
                .validationStatus(ValidationStatus.UNKNOWN).build());
        entityManager.persist(TrainingCourseDetail.builder().version(version).courseId("course-" + opportunity.getId())
                .courseRound(1).trainingStartDate(TODAY.plusDays(startDays)).build());
        return opportunity;
    }

    private Opportunity opportunity(String title, OpportunityType type, String category, String subCategory,
                                    String detailCategory) {
        Opportunity opportunity = Opportunity.builder().title(title).opportunityType(type).build();
        entityManager.persist(opportunity);
        OpportunityVersion version = newVersion(opportunity, 1);
        opportunity.changeCurrentVersion(version);
        opportunity.updateSummary(title, category, subCategory, detailCategory, null);
        return opportunity;
    }

    private OpportunityVersion newVersion(Opportunity opportunity, int versionNo) {
        Map<String, Object> snapshot = opportunity.getOpportunityType() == OpportunityType.VOCATIONAL_TRAINING
                ? Map.of("region", Map.of("status", "SPECIFIC", "codes", List.of("1100000000"),
                        "validationStatus", "UNVERIFIED"))
                : Map.of("title", opportunity.getTitle(), "version", versionNo);
        OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity).versionNo(versionNo)
                .normalizedSnapshot(snapshot).personalizationStatus(PersonalizationStatus.PERSONALIZABLE)
                .normalizationVersion("test").build();
        entityManager.persist(version);
        return version;
    }

    private void window(OpportunityVersion version, int deadlineDays) {
        entityManager.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.FIXED)
                .startDate(TODAY.minusDays(1)).endDate(TODAY.plusDays(deadlineDays))
                .validationStatus(ValidationStatus.VALID).build());
    }

    private void regionRow(OpportunityVersion version, Region region, RegionRole role) {
        entityManager.persist(OpportunityRegion.builder().version(version).region(region).regionRole(role).build());
    }

    private void regionCondition(OpportunityVersion version, String status, RegionRole role,
                                 ValidationStatus validation) {
        entityManager.persist(OpportunityCondition.builder().version(version).conditionType(ConditionType.REGION)
                .requirementType(RequirementType.REQUIRED).conditionField("residenceRegion")
                .expression(Map.of("status", status, "role", role.name())).evidence(Map.of())
                .validationStatus(validation).build());
    }

    private void result(User owner, Opportunity opportunity, EligibilityStatus status, Long profileVersion,
                        String ruleVersion, LocalDate validUntil) {
        EligibilityResultFixtures.result(entityManager, owner.getId(), opportunity, status,
                profileVersion, ruleVersion, validUntil);
    }

    private void preference(User owner, Opportunity opportunity, boolean saved, boolean hidden) {
        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(owner).opportunity(opportunity).build();
        if (saved) preference.markSaved(opportunity.getCurrentVersion().getId());
        if (hidden) preference.markHidden();
        entityManager.persist(preference);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();
    }

    /** fixture 생성과 요청 SQL을 분리해 현재 테스트 스레드의 실행문만 기록한다. */
    public static class SqlCapture implements StatementInspector {

        private static final ThreadLocal<List<String>> CAPTURED = new ThreadLocal<>();

        static void start() {
            CAPTURED.set(new ArrayList<>());
        }

        static List<String> stop() {
            List<String> statements = CAPTURED.get();
            CAPTURED.remove();
            return statements == null ? List.of() : List.copyOf(statements);
        }

        @Override
        public String inspect(String sql) {
            List<String> statements = CAPTURED.get();
            if (statements != null) {
                statements.add(sql);
            }
            return sql;
        }
    }
}
