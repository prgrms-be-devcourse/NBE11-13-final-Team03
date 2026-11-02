package com.team3.backend.search.controller;

import com.jayway.jsonpath.JsonPath;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.domain.entity.EducationLevel;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.eligibility.support.EligibilityResultFixtures;
import com.team3.backend.fixture.BackendPostgres;
import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityCategory;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.entity.YouthPolicyDetail;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 검색 목록·상세·추천·저장 목록이 청년정책의 정규화된 분류 행(categories)을 같은 값으로 주고,
 * 목록은 항목 수와 무관하게 분류 행을 한 번만 읽는지(N+1 없음) 검증한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "collection.schedule.enabled=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.team3.backend.search.controller.OpportunityCategoryDisplayPostgresTest$SqlCapture"
})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("postgres")
class OpportunityCategoryDisplayPostgresTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final String KEYWORD = "분류표시";

    @Container
    static final PostgreSQLContainer postgres = BackendPostgres.create();

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        BackendPostgres.register(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private RegionDirectory regionDirectory;

    @AfterEach
    void clearRegionIndex() {
        regionDirectory.refresh();
    }

    @Test
    void sameOpportunityShowsSameNormalizedCategoriesInSearchDetailRecommendationAndSaved() throws Exception {
        User owner = owner(7101L);
        // 원문은 쉼표로 이은 옛 이름이지만 분류 행은 data-service가 나누고 현재 이름으로 바꾼 값이다
        Opportunity paired = policy(KEYWORD + " 짝", "일자리,교육･직업훈련", "취업,미래역량강화",
                new String[]{"일자리", "취업"}, new String[]{"교육", "미래역량강화"});
        Opportunity repeated = policy(KEYWORD + " 같은 대분류", "일자리,일자리", "취업,창업",
                new String[]{"일자리", "취업"}, new String[]{"일자리", "창업"});
        Opportunity unpaired = policy(KEYWORD + " 개수 불일치", "일자리,교육", "취업,창업,미래역량강화",
                new String[]{"일자리", null}, new String[]{"교육", null},
                new String[]{null, "취업"}, new String[]{null, "창업"}, new String[]{null, "미래역량강화"});
        Opportunity pending = policy(KEYWORD + " 백필 전", "주거", "주거지원");
        Opportunity training = training(KEYWORD + " 훈련", "정보통신", "정보기술");
        List<Opportunity> all = List.of(paired, repeated, unpaired, pending, training);
        for (Opportunity opportunity : all) {
            eligible(owner, opportunity);
            saved(owner, opportunity);
        }
        flushAndClear();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put(paired.getId().toString(), List.of(row("일자리", "취업"), row("교육", "미래역량강화")));
        expected.put(repeated.getId().toString(), List.of(row("일자리", "취업"), row("일자리", "창업")));
        expected.put(unpaired.getId().toString(), List.of(row("일자리", null), row("교육", null),
                row(null, "취업"), row(null, "창업"), row(null, "미래역량강화")));
        expected.put(pending.getId().toString(), List.of());
        expected.put(training.getId().toString(), List.of());

        List<Map<String, Object>> search = JsonPath.read(body(get("/api/opportunities")
                .with(user(principal(owner))).param("keyword", KEYWORD)), "$.content");
        List<Map<String, Object>> savedList = JsonPath.read(body(get("/api/users/me/saved-opportunities")
                .with(user(principal(owner)))), "$.content");
        List<Map<String, Object>> recommendations = JsonPath.read(body(get("/api/users/me/recommendations")
                .with(user(principal(owner)))), "$");
        assertEquals(all.size(), search.size());
        assertEquals(all.size(), savedList.size());
        assertTrue(recommendations.size() >= 4, "추천 결과가 비교에 충분해야 한다");

        for (List<Map<String, Object>> items : List.of(search, savedList, recommendations)) {
            for (Map<String, Object> item : items) {
                String id = item.get("opportunityId").toString();
                assertEquals(expected.get(id), item.get("categories"), "공고 " + id);
            }
        }
        for (Opportunity opportunity : all) {
            Map<String, Object> detail = JsonPath.read(body(get("/api/opportunities/{id}", opportunity.getId())), "$");
            assertEquals(expected.get(opportunity.getId().toString()), detail.get("categories"));
            // 기존 원문 필드는 바뀌지 않는다
            assertEquals(opportunity.getCategory(), detail.get("category"));
            assertEquals(opportunity.getSubCategory(), detail.get("subCategory"));
        }
        Map<String, Object> pairedSearch = search.stream()
                .filter(item -> item.get("opportunityId").toString().equals(paired.getId().toString()))
                .findFirst().orElseThrow();
        assertEquals("일자리,교육･직업훈련", pairedSearch.get("category"));
        assertEquals("취업,미래역량강화", pairedSearch.get("subCategory"));
    }

    @Test
    void listsReadCategoryRowsOnceRegardlessOfItemCountAndDetailAddsOneQueryOnlyForPolicy() throws Exception {
        User owner = owner(7201L);
        Opportunity first = policy(KEYWORD + " 첫 정책", "일자리", "취업", new String[]{"일자리", "취업"});
        eligible(owner, first);
        saved(owner, first);
        flushAndClear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            List<String> searchOne = capture(statistics, searchRequest(owner));
            List<String> savedOne = capture(statistics, get("/api/users/me/saved-opportunities").with(user(principal(owner))));
            List<String> recommendOne = capture(statistics, get("/api/users/me/recommendations").with(user(principal(owner))));

            for (int index = 0; index < 3; index++) {
                Opportunity next = policy(KEYWORD + " 추가 정책 " + index, "일자리,교육", "취업,미래역량강화",
                        new String[]{"일자리", "취업"}, new String[]{"교육", "미래역량강화"});
                eligible(owner, next);
                saved(owner, next);
            }
            flushAndClear();

            List<String> searchMany = capture(statistics, searchRequest(owner));
            List<String> savedMany = capture(statistics, get("/api/users/me/saved-opportunities").with(user(principal(owner))));
            List<String> recommendMany = capture(statistics, get("/api/users/me/recommendations").with(user(principal(owner))));

            // 청년정책 1건과 4건의 SQL 수가 같다 (공고별 추가 조회 없음)
            assertEquals(searchOne.size(), searchMany.size());
            assertEquals(savedOne.size(), savedMany.size());
            assertEquals(recommendOne.size(), recommendMany.size());

            // 직업훈련이 섞여도 분류 행 조회는 1회다. 추천은 기존 선정 경로가 훈련 일정을 유형별로 한 번 더 읽는다(분류와 무관)
            Opportunity training = training(KEYWORD + " 추가 훈련", "정보통신", "정보기술");
            eligible(owner, training);
            saved(owner, training);
            flushAndClear();
            List<String> searchMixed = capture(statistics, searchRequest(owner));
            List<String> savedMixed = capture(statistics, get("/api/users/me/saved-opportunities").with(user(principal(owner))));
            List<String> recommendMixed = capture(statistics, get("/api/users/me/recommendations").with(user(principal(owner))));
            assertEquals(searchOne.size(), searchMixed.size());
            assertEquals(savedOne.size(), savedMixed.size());
            assertEquals(recommendOne.size() + 1, recommendMixed.size());

            for (List<String> sql : List.of(searchOne, savedOne, recommendOne, searchMany, savedMany, recommendMany,
                    searchMixed, savedMixed, recommendMixed)) {
                assertEquals(1, displayCategoryQueries(sql), sql.toString());
            }

            // 청년정책이 없는 페이지와 빈 페이지는 분류 행을 읽지 않는다
            List<String> trainingOnly = capture(statistics, searchRequest(owner).param("type", "VOCATIONAL_TRAINING"));
            List<String> emptyPage = capture(statistics, searchRequest(owner).param("page", "9"));
            assertEquals(0, displayCategoryQueries(trainingOnly));
            assertEquals(0, displayCategoryQueries(emptyPage));

            // 상세: 청년정책만 분류 행 1회가 늘고, 판정 조회 이후 공고·신청기간을 다시 읽지 않는다
            List<String> policyDetail = capture(statistics, get("/api/opportunities/{id}", first.getId())
                    .with(user(principal(owner))));
            List<String> trainingDetail = capture(statistics, get("/api/opportunities/{id}", training.getId())
                    .with(user(principal(owner))));
            assertEquals(1, displayCategoryQueries(policyDetail));
            assertEquals(0, displayCategoryQueries(trainingDetail));
            assertEquals(trainingDetail.size() + 1, policyDetail.size());
            for (List<String> sql : List.of(policyDetail, trainingDetail)) {
                // 공고 단건·신청기간은 한 번씩만 읽는다. 판정 조회는 공고를 조인하는 한 문장이며 별도 공고·기간 조회를 하지 않는다
                assertEquals(1, count(sql, "application_windows"), sql.toString());
                assertEquals(1, count(sql, "from application.opportunities o1_0 where"), sql.toString());
                assertEquals(1, count(sql, "eligibility_set_results"), sql.toString());
            }

            System.out.printf("CATEGORY_DISPLAY_SQL search=%d/%d/%d saved=%d/%d/%d recommendations=%d/%d/%d "
                            + "trainingOnlySearch=%d emptySearch=%d policyDetail=%d trainingDetail=%d%n",
                    searchOne.size(), searchMany.size(), searchMixed.size(),
                    savedOne.size(), savedMany.size(), savedMixed.size(),
                    recommendOne.size(), recommendMany.size(), recommendMixed.size(),
                    trainingOnly.size(), emptyPage.size(), policyDetail.size(), trainingDetail.size());
            System.out.println("CATEGORY_DISPLAY_DETAIL_SQL policy=" + summary(policyDetail)
                    + " training=" + summary(trainingDetail));
        } finally {
            SqlCapture.stop();
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
    }

    private MockHttpServletRequestBuilder searchRequest(User owner) {
        return get("/api/opportunities").with(user(principal(owner))).param("keyword", KEYWORD);
    }

    /** 요청 하나가 실행한 SQL. 1차 캐시가 결과에 끼지 않도록 비우고 실행한다 */
    private List<String> capture(Statistics statistics, MockHttpServletRequestBuilder request) throws Exception {
        entityManager.clear();
        statistics.clear();
        SqlCapture.start();
        mockMvc.perform(request).andReturn();
        List<String> sql = SqlCapture.stop();
        assertEquals(statistics.getPrepareStatementCount(), sql.size());
        return sql;
    }

    /** 응답 표시용 분류 행 조회. 추천 선정 SQL의 관심 적합도 집계도 opportunity_categories를 읽지만 정렬 순서는 쓰지 않는다 */
    private static long displayCategoryQueries(List<String> sql) {
        return sql.stream().filter(statement -> statement.contains("opportunity_categories")
                && statement.contains("sort_order")).count();
    }

    /** 실행한 SQL의 첫 FROM 테이블 목록 (결과 보고용) */
    private static List<String> summary(List<String> sql) {
        return sql.stream().map(statement -> {
            String compact = statement.replaceAll("\\s+", " ");
            int from = compact.toLowerCase().indexOf(" from ");
            return from < 0 ? compact : compact.substring(from + 6, Math.min(compact.length(), from + 60));
        }).toList();
    }

    private static long count(List<String> sql, String fragment) {
        return sql.stream().filter(statement -> statement.contains(fragment)).count();
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        entityManager.clear();
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return response.getContentAsString();
    }

    private static Map<String, Object> row(String large, String medium) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("largeCategory", large);
        row.put("mediumCategory", medium);
        return row;
    }

    private User owner(Long kakaoId) {
        Region seoul = Region.builder().regionCode("1100000000").regionName("서울").build();
        entityManager.persist(seoul);
        User owner = User.builder().kakaoId(kakaoId).role(Role.USER).build();
        entityManager.persist(owner);
        entityManager.persist(EligibilityProfile.builder().userId(owner.getId())
                .birthDate(LocalDate.of(1998, 1, 1)).residenceRegion(seoul)
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY).build());
        return owner;
    }

    /** 분류 행을 직접 넣는 청년정책. 행을 주지 않으면 백필 전 정책이다 */
    private Opportunity policy(String title, String category, String subCategory, String[]... rows) {
        Opportunity opportunity = opportunity(title, OpportunityType.YOUTH_POLICY, category, subCategory, null);
        OpportunityVersion version = opportunity.getCurrentVersion();
        entityManager.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.FIXED)
                .startDate(TODAY.minusDays(1)).endDate(TODAY.plusDays(5))
                .validationStatus(ValidationStatus.VALID).build());
        entityManager.persist(YouthPolicyDetail.builder().version(version).policyNo("P-" + opportunity.getId()).build());
        short order = 0;
        for (String[] row : rows) {
            entityManager.persist(OpportunityCategory.builder().version(version)
                    .sortOrder(order++).largeCategory(row[0]).mediumCategory(row[1]).build());
        }
        return opportunity;
    }

    private Opportunity training(String title, String subCategory, String detailCategory) {
        Opportunity opportunity = opportunity(title, OpportunityType.VOCATIONAL_TRAINING,
                "교육･직업훈련", subCategory, detailCategory);
        OpportunityVersion version = opportunity.getCurrentVersion();
        entityManager.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.UNKNOWN)
                .validationStatus(ValidationStatus.UNKNOWN).build());
        entityManager.persist(TrainingCourseDetail.builder().version(version).courseId("course-" + opportunity.getId())
                .courseRound(1).trainingStartDate(TODAY.plusDays(10)).build());
        return opportunity;
    }

    private Opportunity opportunity(String title, OpportunityType type, String category, String subCategory,
                                    String detailCategory) {
        Opportunity opportunity = Opportunity.builder().title(title).opportunityType(type).build();
        entityManager.persist(opportunity);
        OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity).versionNo(1)
                .normalizedSnapshot(Map.of("title", title))
                .personalizationStatus(PersonalizationStatus.PERSONALIZABLE)
                .normalizationVersion("test").build();
        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
        opportunity.updateSummary(title, category, subCategory, detailCategory, null);
        return opportunity;
    }

    private void eligible(User owner, Opportunity opportunity) {
        EligibilityResultFixtures.result(entityManager, owner.getId(), opportunity, EligibilityStatus.ELIGIBLE,
                1L, EligibilityResultService.RULE_VERSION, TODAY.plusDays(20));
    }

    private void saved(User owner, Opportunity opportunity) {
        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(owner).opportunity(opportunity).build();
        preference.markSaved(opportunity.getCurrentVersion().getId());
        entityManager.persist(preference);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();
    }

    private static CustomUserDetails principal(User owner) {
        return new CustomUserDetails(owner.getId(), Role.USER, "category-display-test");
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
