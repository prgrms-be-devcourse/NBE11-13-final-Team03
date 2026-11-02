package com.team3.backend.search.controller;

import com.jayway.jsonpath.JsonPath;
import com.team3.backend.fixture.BackendPostgres;
import com.team3.backend.fixture.PolicyCategoryRows;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityCategory;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 청년정책 다중 대·중분류 검색. 청년정책은 현재 버전의 분류 행(opportunity_categories)으로, 직업훈련은 공고 컬럼으로 거른다.
 * 대·중분류를 함께 주면 같은 분류 행에서 둘 다 맞아야 하고, 분류 행이 여러 개여도 공고는 한 번만 나오며 건수가 정확해야 한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("postgres")
class OpportunitySearchCategoryPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = BackendPostgres.create();

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        BackendPostgres.register(registry, postgres);
    }

    private static final String KEYWORD = "다중분류";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;

    private Opportunity jobAndEducation;
    private Opportunity duplicateLarge;
    private Opportunity single;
    private Opportunity mediumOnly;
    private Opportunity largeOnly;
    private Opportunity pendingBackfill;
    private Opportunity noCategory;
    private Opportunity training;
    private Opportunity otherTraining;

    @BeforeEach
    void fixture() {
        // (일자리, 취업), (교육, 미래역량강화) — 원문 "일자리,교육" / "취업,미래역량강화"
        jobAndEducation = policy(KEYWORD + " 일자리 교육", "일자리,교육", "취업,미래역량강화",
                pair("일자리", "취업"), pair("교육", "미래역량강화"));
        // 같은 대분류 두 행 — 원문 "일자리,일자리" / "취업,창업"
        duplicateLarge = policy(KEYWORD + " 일자리 중복", "일자리,일자리", "취업,창업",
                pair("일자리", "취업"), pair("일자리", "창업"));
        single = policy(KEYWORD + " 단일 교육", "교육", "미래역량강화", pair("교육", "미래역량강화"));
        // 개수가 맞지 않는 원본: 대분류만 있는 행과 중분류만 있는 행
        mediumOnly = policy(KEYWORD + " 중분류만", "교육,복지", "취업", pair("교육", null), pair("복지", null),
                pair(null, "취업"));
        largeOnly = policy(KEYWORD + " 대분류만", "주거", null, pair("주거", null));
        // 공고 컬럼에는 분류가 있지만 분류 행이 아직 없는 백필 전 공고
        pendingBackfill = policy(KEYWORD + " 백필 전", "교육", "미래역량강화");
        noCategory = policy(KEYWORD + " 분류 없음", null, null);
        training = training(KEYWORD + " 훈련 정보통신", "정보통신", "정보기술");
        otherTraining = training(KEYWORD + " 훈련 경영", "경영", "경영기획");
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void largeOrMediumAloneMatchesAnyCategoryRowOfCurrentVersion() throws Exception {
        assertThat(ids(search().param("type", "YOUTH_POLICY").param("category", "교육")))
                .containsExactlyInAnyOrder(id(jobAndEducation), id(single), id(mediumOnly));
        assertThat(ids(search().param("type", "YOUTH_POLICY").param("subCategory", "취업")))
                .containsExactlyInAnyOrder(id(jobAndEducation), id(duplicateLarge), id(mediumOnly));
        assertThat(ids(search().param("type", "YOUTH_POLICY").param("category", "주거")))
                .containsExactly(id(largeOnly));
        // 원문 쉼표 문자열 자체는 분류값이 아니다
        assertThat(ids(search().param("category", "일자리,교육"))).isEmpty();
    }

    @Test
    void largeAndMediumTogetherMustMatchTheSameRow() throws Exception {
        assertThat(ids(search().param("type", "YOUTH_POLICY").param("category", "교육").param("subCategory", "취업")))
                .as("(일자리, 취업)·(교육, 미래역량강화) 공고는 교육 + 취업에 맞지 않는다").isEmpty();
        assertThat(ids(search().param("type", "YOUTH_POLICY").param("category", "일자리").param("subCategory", "취업")))
                .containsExactlyInAnyOrder(id(jobAndEducation), id(duplicateLarge));
        assertThat(ids(search().param("type", "YOUTH_POLICY").param("category", "교육").param("subCategory", "미래역량강화")))
                .containsExactlyInAnyOrder(id(jobAndEducation), id(single));
        // 대분류만 있는 행·중분류만 있는 행은 짝 조건을 채우지 않는다
        assertThat(ids(search().param("category", "교육").param("subCategory", "취업"))).isEmpty();
    }

    @Test
    void duplicateLargeRowsReturnOpportunityOnceWithExactCountsAcrossPages() throws Exception {
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = body(search().param("category", "일자리").param("sort", "LATEST")
                    .param("page", String.valueOf(page)).param("size", "1"));
            assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(2);
            assertThat(JsonPath.<Integer>read(body, "$.totalPages")).isEqualTo(2);
            assertThat(JsonPath.<Boolean>read(body, "$.hasNext")).isEqualTo(page == 0);
            paged.addAll(ids(body));
        }
        assertThat(paged).containsExactly(id(duplicateLarge), id(jobAndEducation));
        assertThat(new HashSet<>(paged)).hasSize(paged.size());
    }

    @Test
    void policyWithoutCategoryRowsIsExcludedUntilBackfillAndThenMatches() throws Exception {
        assertThat(ids(search().param("category", "교육").param("subCategory", "미래역량강화")))
                .as("백필 전: 공고 컬럼 문자열로 대체하지 않는다")
                .containsExactlyInAnyOrder(id(jobAndEducation), id(single));
        // 분류 필터가 없으면 백필 전·분류 없는 공고도 그대로 검색된다
        assertThat(ids(search().param("type", "YOUTH_POLICY")))
                .contains(id(pendingBackfill), id(noCategory));

        assertThat(PolicyCategoryRows.backfill(entityManager)).as("분류가 있는 백필 전 공고만 채운다").isEqualTo(1);
        entityManager.clear();

        assertThat(ids(search().param("category", "교육").param("subCategory", "미래역량강화")))
                .as("백필 후")
                .containsExactlyInAnyOrder(id(jobAndEducation), id(single), id(pendingBackfill));
        assertThat(ids(search().param("category", "교육"))).doesNotContain(id(noCategory));
    }

    @Test
    void trainingKeepsColumnFiltersAndMixesWithPoliciesInUntypedSearch() throws Exception {
        assertThat(ids(search().param("type", "VOCATIONAL_TRAINING").param("category", "교육･직업훈련")
                .param("subCategory", "정보통신").param("detailCategory", "정보기술")))
                .containsExactly(id(training));
        assertThat(ids(search().param("subCategory", "정보통신"))).containsExactly(id(training));
        assertThat(ids(search().param("category", "교육･직업훈련")))
                .containsExactlyInAnyOrder(id(training), id(otherTraining));
        // 유형 없이 정책 분류로 찾으면 정책만, 직업훈련 분류로 찾으면 훈련만 나온다
        assertThat(ids(search().param("subCategory", "미래역량강화")))
                .containsExactlyInAnyOrder(id(jobAndEducation), id(single));
        // 청년정책은 세분류가 없어 세분류 필터에 맞지 않는다 (기존 동작)
        assertThat(ids(search().param("type", "YOUTH_POLICY").param("detailCategory", "정보기술"))).isEmpty();
    }

    /**
     * 청년정책과 직업훈련이 같은 원본 대분류명 '교육･직업훈련'을 쓴다 (화면의 '직업훈련'은 직업훈련 대분류의 표시 이름일 뿐이다).
     * type 또는 categoryType으로 분류를 찾을 유형을 정하면 그 유형의 공고만 나온다. 둘 다 없으면 기존처럼 두 유형에서 찾는다.
     */
    @Test
    void sameLargeCategoryNameIsSearchedPerOpportunityTypeWithCategoryType() throws Exception {
        Opportunity future = policy(KEYWORD + " 정책 미래역량", "교육･직업훈련", "미래역량강화",
                pair("교육･직업훈련", "미래역량강화"));
        Opportunity online = policy(KEYWORD + " 정책 온오프라인", "교육･직업훈련,교육･직업훈련", "온·오프라인교육 ,미래역량강화",
                pair("교육･직업훈련", "온·오프라인교육"), pair("교육･직업훈련", "미래역량강화"));
        entityManager.flush();
        entityManager.clear();
        Set<Long> policies = Set.of(id(future), id(online));
        Set<Long> trainings = Set.of(id(training), id(otherTraining));

        assertThat(ids(search().param("type", "YOUTH_POLICY").param("category", "교육･직업훈련")))
                .containsExactlyInAnyOrderElementsOf(policies);
        assertThat(ids(search().param("type", "VOCATIONAL_TRAINING").param("category", "교육･직업훈련")))
                .containsExactlyInAnyOrderElementsOf(trainings);
        // 유형 전체 + 분류 유형: 선택한 분류의 유형만 나온다
        assertThat(ids(search().param("categoryType", "YOUTH_POLICY").param("category", "교육･직업훈련")))
                .containsExactlyInAnyOrderElementsOf(policies);
        assertThat(ids(search().param("categoryType", "VOCATIONAL_TRAINING").param("category", "교육･직업훈련")))
                .containsExactlyInAnyOrderElementsOf(trainings);
        assertThat(ids(search().param("categoryType", "YOUTH_POLICY").param("category", "교육･직업훈련")
                .param("subCategory", "온·오프라인교육"))).containsExactly(id(online));
        assertThat(ids(search().param("categoryType", "VOCATIONAL_TRAINING").param("category", "교육･직업훈련")
                .param("subCategory", "미래역량강화"))).as("직업훈련에는 이 중분류가 없다").isEmpty();
        assertThat(ids(search().param("categoryType", "VOCATIONAL_TRAINING").param("detailCategory", "정보기술")))
                .containsExactly(id(training));
        assertThat(ids(search().param("categoryType", "YOUTH_POLICY").param("detailCategory", "정보기술"))).isEmpty();
        // 분류 유형 생략(기존 요청): 두 유형 모두
        Set<Long> both = new HashSet<>(policies);
        both.addAll(trainings);
        assertThat(ids(search().param("category", "교육･직업훈련"))).containsExactlyInAnyOrderElementsOf(both);
        // categoryType이 type과 같으면 허용, 다르거나 분류 값이 없으면 400
        assertThat(ids(search().param("type", "YOUTH_POLICY").param("categoryType", "YOUTH_POLICY")
                .param("category", "교육･직업훈련"))).containsExactlyInAnyOrderElementsOf(policies);
        mockMvc.perform(search().param("type", "YOUTH_POLICY").param("categoryType", "VOCATIONAL_TRAINING")
                .param("category", "교육･직업훈련")).andExpect(status().isBadRequest());
        mockMvc.perform(search().param("categoryType", "YOUTH_POLICY")).andExpect(status().isBadRequest());
        mockMvc.perform(search().param("categoryType", "POLICY").param("category", "교육")).andExpect(status().isBadRequest());
        // 건수도 선택한 유형만 센다
        assertThat(JsonPath.<Integer>read(body(search().param("categoryType", "YOUTH_POLICY")
                .param("category", "교육･직업훈련").param("size", "1")), "$.totalElements")).isEqualTo(2);
    }

    @Test
    void categoryFilterKeepsRelevanceAndLatestOrderOfUnfilteredSearch() throws Exception {
        for (String sort : List.of("RELEVANCE", "LATEST", "OLDEST")) {
            Set<Long> expected = Set.of(id(jobAndEducation), id(single), id(mediumOnly));
            List<Long> unfiltered = ids(search().param("sort", sort).param("size", "100"));
            List<Long> filtered = ids(search().param("sort", sort).param("category", "교육").param("size", "100"));
            assertThat(filtered).as(sort)
                    .containsExactlyElementsOf(unfiltered.stream().filter(expected::contains).toList());
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder search() {
        return get("/api/opportunities").param("keyword", KEYWORD);
    }

    private List<Long> ids(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return ids(body(request));
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<Long> ids(String body) {
        List<Number> ids = JsonPath.read(body, "$.content[*].opportunityId");
        return ids.stream().map(Number::longValue).toList();
    }

    private static Long id(Opportunity opportunity) {
        return opportunity.getId();
    }

    private static String[] pair(String large, String medium) {
        return new String[]{large, medium};
    }

    private Opportunity policy(String title, String rawLarge, String rawMedium, String[]... rows) {
        Opportunity opportunity = opportunity(title, OpportunityType.YOUTH_POLICY);
        opportunity.updateSummary(title, rawLarge, rawMedium, null, null);
        short order = 0;
        for (String[] row : rows) {
            entityManager.persist(OpportunityCategory.builder().version(opportunity.getCurrentVersion())
                    .sortOrder(order++).largeCategory(row[0]).mediumCategory(row[1]).build());
        }
        return opportunity;
    }

    private Opportunity training(String title, String sub, String detail) {
        Opportunity opportunity = opportunity(title, OpportunityType.VOCATIONAL_TRAINING);
        opportunity.updateSummary(title, "교육･직업훈련", sub, detail, null);
        return opportunity;
    }

    private Opportunity opportunity(String title, OpportunityType type) {
        Opportunity opportunity = Opportunity.builder().title(title).opportunityType(type).build();
        entityManager.persist(opportunity);
        OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity).versionNo(1)
                .normalizedSnapshot(Map.of("title", title)).personalizationStatus(PersonalizationStatus.UNKNOWN)
                .normalizationVersion("1").build();
        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
        // 최신순 정렬이 고정되도록 생성 순서대로 시각이 다르게 저장된다 (createdAt은 엔티티가 채운다)
        entityManager.flush();
        return opportunity;
    }
}
