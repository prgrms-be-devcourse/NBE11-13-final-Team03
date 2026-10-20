package com.team3.backend.search.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.domain.entity.EducationLevel;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.entity.RequirementType;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpportunitySearchPostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("yon_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private RegionDirectory regionDirectory;

    @Test
    void combinedPolicyFiltersSortAndPageWithoutExcludingSavedHiddenOpportunity() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Region province = Region.builder().regionCode("1100000000").regionName("Seoul").build();
        Region elsewhere = Region.builder().regionCode("2600000000").regionName("Busan").build();
        entityManager.persist(province);
        entityManager.persist(elsewhere);
        User account = User.builder().kakaoId(990001L).role(Role.USER).build();
        entityManager.persist(account);
        Long userId = account.getId();
        entityManager.persist(EligibilityProfile.builder()
                .userId(userId).birthDate(LocalDate.of(1998, 1, 1))
                .residenceRegion(province).employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY).build());

        Opportunity near = opportunity("PG final near", OpportunityType.YOUTH_POLICY);
        Opportunity middle = opportunity("PG final middle", OpportunityType.YOUTH_POLICY);
        Opportunity far = opportunity("PG final far", OpportunityType.YOUTH_POLICY);
        Opportunity possible = opportunity("PG final possible", OpportunityType.YOUTH_POLICY);
        Opportunity wrongSubCategory = opportunity("PG final other subcategory", OpportunityType.YOUTH_POLICY);
        Opportunity wrongRegion = opportunity("PG final other region", OpportunityType.YOUTH_POLICY);
        Opportunity outsideDate = opportunity("PG final outside date", OpportunityType.YOUTH_POLICY);
        Opportunity upcoming = opportunity("PG final upcoming", OpportunityType.YOUTH_POLICY);
        Opportunity wrongCategory = opportunity("PG final other category", OpportunityType.YOUTH_POLICY);
        for (Opportunity policy : List.of(near, middle, far, possible, wrongSubCategory,
                wrongRegion, outsideDate, upcoming, wrongCategory)) {
            policy.updateSummary(policy.getTitle(),
                    policy == wrongCategory ? "housing" : "career",
                    policy == wrongSubCategory ? "support" : "job", null);
            entityManager.persist(OpportunityRegion.builder().version(policy.getCurrentVersion())
                    .region(policy == wrongRegion ? elsewhere : province)
                    .regionRole(RegionRole.RESIDENCE).build());
            LocalDate start = policy == upcoming ? today.plusDays(1) : today.minusDays(1);
            LocalDate end = policy == near ? today.plusDays(1)
                    : policy == far ? today.plusDays(3)
                    : policy == outsideDate ? today.plusDays(5) : today.plusDays(2);
            window(policy, PeriodType.FIXED, start, end, ValidationStatus.VALID);
            eligibilityResult(userId, policy,
                    policy == possible ? EligibilityStatus.POSSIBLY_ELIGIBLE : EligibilityStatus.ELIGIBLE,
                    null, 1L, EligibilityResultService.RULE_VERSION, null);
        }
        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(account).opportunity(near).build();
        preference.markSaved();
        preference.markHidden();
        entityManager.persist(preference);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        CustomUserDetails principal = new CustomUserDetails(userId, Role.USER, "pg-final");
        List<Long> actualIds = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            MvcResult result = mockMvc.perform(get("/api/opportunities").with(user(principal))
                            .param("keyword", "PG final")
                            .param("type", "YOUTH_POLICY")
                            .param("region", province.getRegionCode())
                            .param("category", "career")
                            .param("subCategory", "job")
                            .param("applicationStatus", "OPEN")
                            .param("deadlineFrom", today.plusDays(1).toString())
                            .param("deadlineTo", today.plusDays(3).toString())
                            .param("eligibilityStatuses", "ELIGIBLE")
                            .param("sort", "DEADLINE_ASC")
                            .param("page", String.valueOf(page)).param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.hasNext").value(page == 0))
                    .andExpect(jsonPath("$.content.length()").value(page == 0 ? 2 : page == 1 ? 1 : 0))
                    .andReturn();
            actualIds.addAll(opportunityIds(result));
        }
        assertEquals(List.of(near.getId(), middle.getId(), far.getId()), actualIds);
        assertEquals(actualIds.size(), new HashSet<>(actualIds).size());

        MvcResult publicSearch = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG final near"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(near.getId()), opportunityIds(publicSearch));
    }

    @Test
    void eligibilityFilterUsesFreshResultsBeforePostgresPagination() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Long userId = 1001L;
        Long otherUserId = 1002L;
        Region region = Region.builder().regionCode("1100000000").regionName("Seoul").build();
        entityManager.persist(region);
        for (Long profileUserId : List.of(userId, otherUserId, 1004L)) {
            entityManager.persist(EligibilityProfile.builder()
                    .userId(profileUserId).birthDate(LocalDate.of(1998, 1, 1))
                    .residenceRegion(region).employmentStatus(EmploymentStatus.UNEMPLOYED)
                    .educationLevel(EducationLevel.UNIVERSITY).build());
        }

        Opportunity eligible = opportunity("PG eligibility eligible", OpportunityType.YOUTH_POLICY);
        Opportunity possible = opportunity("PG eligibility possible", OpportunityType.YOUTH_POLICY);
        Opportunity notEligible = opportunity("PG eligibility not eligible", OpportunityType.YOUTH_POLICY);
        Opportunity unknown = opportunity("PG eligibility unknown", OpportunityType.YOUTH_POLICY);
        Opportunity staleOpportunity = opportunity("PG eligibility stale opportunity", OpportunityType.YOUTH_POLICY);
        Opportunity staleProfile = opportunity("PG eligibility stale profile", OpportunityType.YOUTH_POLICY);
        Opportunity staleRule = opportunity("PG eligibility stale rule", OpportunityType.YOUTH_POLICY);
        Opportunity expired = opportunity("PG eligibility expired", OpportunityType.YOUTH_POLICY);
        Opportunity noResult = opportunity("PG eligibility no result", OpportunityType.YOUTH_POLICY);
        Opportunity otherOnly = opportunity("PG eligibility other only", OpportunityType.YOUTH_POLICY);
        Opportunity trainingToday = opportunity("PG eligibility training today", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity trainingPast = opportunity("PG eligibility training past", OpportunityType.VOCATIONAL_TRAINING);
        eligible.updateSummary(eligible.getTitle(), "career", "job", null);
        trainingToday.updateSummary(trainingToday.getTitle(), "career", "course", null);
        trainingPast.updateSummary(trainingPast.getTitle(), "career", "course", null);
        entityManager.persist(OpportunityRegion.builder().version(eligible.getCurrentVersion())
                .region(region).regionRole(RegionRole.RESIDENCE).build());
        for (Opportunity training : List.of(trainingToday, trainingPast)) {
            entityManager.persist(OpportunityRegion.builder().version(training.getCurrentVersion())
                    .region(region).regionRole(RegionRole.IMPLEMENTATION).build());
            window(training, PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN);
        }
        window(eligible, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.VALID);
        trainingDetail(trainingToday, today);
        trainingDetail(trainingPast, today.minusDays(1));

        eligibilityResult(userId, eligible, EligibilityStatus.ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(userId, possible, EligibilityStatus.POSSIBLY_ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, today.plusDays(1));
        eligibilityResult(userId, notEligible, EligibilityStatus.NOT_ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(userId, unknown, EligibilityStatus.UNKNOWN, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(userId, staleOpportunity, EligibilityStatus.ELIGIBLE,
                staleOpportunity.getCurrentVersion().getId() + 1000, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(userId, staleProfile, EligibilityStatus.ELIGIBLE, null, 2L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(userId, staleRule, EligibilityStatus.ELIGIBLE, null, 1L,
                "old-rule", null);
        eligibilityResult(userId, expired, EligibilityStatus.ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, today);
        eligibilityResult(otherUserId, otherOnly, EligibilityStatus.ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(userId, trainingToday, EligibilityStatus.ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(userId, trainingPast, EligibilityStatus.ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        CustomUserDetails principal = new CustomUserDetails(userId, Role.USER, "pg-search");
        MvcResult unfiltered = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(12))
                .andReturn();
        assertEquals(12, opportunityIds(unfiltered).size());

        List<Long> eligibleIds = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            MvcResult result = mockMvc.perform(get("/api/opportunities").with(user(principal))
                            .param("keyword", "PG eligibility")
                            .param("eligibilityStatuses", "ELIGIBLE")
                            .param("page", String.valueOf(page)).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andReturn();
            eligibleIds.addAll(opportunityIds(result));
        }
        assertEquals(Set.of(eligible.getId(), trainingToday.getId(), trainingPast.getId()),
                new HashSet<>(eligibleIds));
        assertEquals(3, new HashSet<>(eligibleIds).size());

        MvcResult combinedStatuses = mockMvc.perform(get("/api/opportunities").with(user(principal))
                        .param("keyword", "PG eligibility")
                        .param("eligibilityStatuses", "ELIGIBLE,POSSIBLY_ELIGIBLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andReturn();
        assertEquals(Set.of(eligible.getId(), possible.getId(), trainingToday.getId(), trainingPast.getId()),
                new HashSet<>(opportunityIds(combinedStatuses)));

        for (Object[] expected : new Object[][]{
                {"NOT_ELIGIBLE", notEligible.getId()}, {"UNKNOWN", unknown.getId()}
        }) {
            MvcResult result = mockMvc.perform(get("/api/opportunities").with(user(principal))
                            .param("keyword", "PG eligibility")
                            .param("eligibilityStatuses", (String) expected[0]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andReturn();
            assertEquals(List.of(expected[1]), opportunityIds(result));
        }

        MvcResult training = mockMvc.perform(get("/api/opportunities").with(user(principal))
                        .param("keyword", "PG eligibility")
                        .param("eligibilityStatuses", "ELIGIBLE")
                        .param("type", "VOCATIONAL_TRAINING")
                        .param("category", "career")
                        .param("region", region.getRegionCode())
                        .param("applicationStatus", "OPEN")
                        .param("startDateFrom", today.toString())
                        .param("sort", "START_DATE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(trainingToday.getId()), opportunityIds(training));

        MvcResult policy = mockMvc.perform(get("/api/opportunities").with(user(principal))
                        .param("keyword", "PG eligibility")
                        .param("eligibilityStatuses", "ELIGIBLE")
                        .param("type", "YOUTH_POLICY")
                        .param("category", "career")
                        .param("region", region.getRegionCode())
                        .param("applicationStatus", "OPEN")
                        .param("deadlineTo", today.plusDays(1).toString())
                        .param("sort", "DEADLINE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(eligible.getId()), opportunityIds(policy));

        MvcResult other = mockMvc.perform(get("/api/opportunities")
                        .with(user(new CustomUserDetails(otherUserId, Role.USER, "other")))
                        .param("keyword", "PG eligibility")
                        .param("eligibilityStatuses", "ELIGIBLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(otherOnly.getId()), opportunityIds(other));
        mockMvc.perform(get("/api/opportunities")
                        .with(user(new CustomUserDetails(1003L, Role.USER, "no-profile")))
                        .param("keyword", "PG eligibility")
                        .param("eligibilityStatuses", "ELIGIBLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/opportunities")
                        .with(user(new CustomUserDetails(1004L, Role.USER, "no-result")))
                        .param("keyword", "PG eligibility")
                        .param("eligibilityStatuses", "ELIGIBLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    private void eligibilityResult(Long userId, Opportunity opportunity, EligibilityStatus status,
                                   Long opportunityVersionId, Long profileVersion, String ruleVersion,
                                   LocalDate validUntil) {
        entityManager.persist(EligibilityResult.builder()
                .userId(userId).opportunityId(opportunity.getId())
                .opportunityVersionId(opportunityVersionId == null
                        ? opportunity.getCurrentVersion().getId() : opportunityVersionId)
                .profileVersion(profileVersion).ruleVersion(ruleVersion)
                .eligibilityStatus(status).conditionResults(List.of())
                .validUntil(validUntil).evaluatedAt(LocalDateTime.now()).build());
    }

    @Test
    void searchDisplaysOnlyCurrentUsersFreshStatusesAndSavedIdsWithoutPerItemQueries() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Region region = Region.builder().regionCode("1171000000").regionName("Songpa").build();
        entityManager.persist(region);
        User account = User.builder().kakaoId(990011L).role(Role.USER).build();
        User otherAccount = User.builder().kakaoId(990012L).role(Role.USER).build();
        User noProfileAccount = User.builder().kakaoId(990013L).role(Role.USER).build();
        entityManager.persist(account);
        entityManager.persist(otherAccount);
        entityManager.persist(noProfileAccount);
        for (Long profileUserId : List.of(account.getId(), otherAccount.getId())) {
            entityManager.persist(EligibilityProfile.builder()
                    .userId(profileUserId).birthDate(LocalDate.of(1998, 1, 1))
                    .residenceRegion(region).employmentStatus(EmploymentStatus.UNEMPLOYED)
                    .educationLevel(EducationLevel.UNIVERSITY).build());
        }

        Opportunity eligible = opportunity("PG display eligible", OpportunityType.YOUTH_POLICY);
        Opportunity possible = opportunity("PG display possible", OpportunityType.YOUTH_POLICY);
        Opportunity notEligible = opportunity("PG display not eligible", OpportunityType.YOUTH_POLICY);
        Opportunity unknown = opportunity("PG display unknown", OpportunityType.YOUTH_POLICY);
        Opportunity staleVersion = opportunity("PG display stale version", OpportunityType.YOUTH_POLICY);
        Opportunity staleProfile = opportunity("PG display stale profile", OpportunityType.YOUTH_POLICY);
        Opportunity staleRule = opportunity("PG display stale rule", OpportunityType.YOUTH_POLICY);
        Opportunity expired = opportunity("PG display expired", OpportunityType.YOUTH_POLICY);
        Opportunity noResult = opportunity("PG display no result", OpportunityType.YOUTH_POLICY);
        eligibilityResult(account.getId(), eligible, EligibilityStatus.ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(account.getId(), possible, EligibilityStatus.POSSIBLY_ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(account.getId(), notEligible, EligibilityStatus.NOT_ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(account.getId(), unknown, EligibilityStatus.UNKNOWN, null, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(account.getId(), staleVersion, EligibilityStatus.ELIGIBLE,
                staleVersion.getCurrentVersion().getId() + 1000, 1L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(account.getId(), staleProfile, EligibilityStatus.ELIGIBLE, null, 2L,
                EligibilityResultService.RULE_VERSION, null);
        eligibilityResult(account.getId(), staleRule, EligibilityStatus.ELIGIBLE, null, 1L,
                "old-rule", null);
        eligibilityResult(account.getId(), expired, EligibilityStatus.ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, today);
        eligibilityResult(otherAccount.getId(), noResult, EligibilityStatus.ELIGIBLE, null, 1L,
                EligibilityResultService.RULE_VERSION, null);

        UserOpportunityPreference both = UserOpportunityPreference.builder()
                .user(account).opportunity(eligible).build();
        both.markSaved();
        both.markHidden();
        entityManager.persist(both);
        UserOpportunityPreference saved = UserOpportunityPreference.builder()
                .user(account).opportunity(unknown).build();
        saved.markSaved();
        entityManager.persist(saved);
        UserOpportunityPreference otherSaved = UserOpportunityPreference.builder()
                .user(otherAccount).opportunity(possible).build();
        otherSaved.markSaved();
        entityManager.persist(otherSaved);
        UserOpportunityPreference noProfileSaved = UserOpportunityPreference.builder()
                .user(noProfileAccount).opportunity(notEligible).build();
        noProfileSaved.markSaved();
        entityManager.persist(noProfileSaved);
        entityManager.flush();
        entityManager.clear();

        CustomUserDetails principal = new CustomUserDetails(account.getId(), Role.USER, "display");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long beforePublic = statistics.getPrepareStatementCount();
            MvcResult publicPage = mockMvc.perform(get("/api/opportunities")
                            .param("keyword", "PG display").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(9))
                    .andExpect(jsonPath("$.totalPages").value(5))
                    .andReturn();
            long publicQueries = statistics.getPrepareStatementCount() - beforePublic;
            for (Map<String, Object> item : searchItems(publicPage).values()) {
                assertNull(item.get("eligibilityStatus"));
                assertEquals(false, item.get("saved"));
            }

            long beforeAuthenticated = statistics.getPrepareStatementCount();
            MvcResult authenticatedPage = mockMvc.perform(get("/api/opportunities")
                            .with(user(principal)).param("keyword", "PG display").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(9))
                    .andExpect(jsonPath("$.totalPages").value(5))
                    .andReturn();
            assertEquals(publicQueries + 2,
                    statistics.getPrepareStatementCount() - beforeAuthenticated);
            assertEquals(opportunityIds(publicPage), opportunityIds(authenticatedPage));

            long beforeLargePage = statistics.getPrepareStatementCount();
            MvcResult largePage = mockMvc.perform(get("/api/opportunities")
                            .with(user(principal)).param("keyword", "PG display"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(9))
                    .andExpect(jsonPath("$.content.length()").value(9))
                    .andReturn();
            assertEquals(3, statistics.getPrepareStatementCount() - beforeLargePage);
            Map<Long, Map<String, Object>> items = searchItems(largePage);
            assertEquals("ELIGIBLE", items.get(eligible.getId()).get("eligibilityStatus"));
            assertEquals(true, items.get(eligible.getId()).get("saved"));
            assertEquals("POSSIBLY_ELIGIBLE", items.get(possible.getId()).get("eligibilityStatus"));
            assertEquals(false, items.get(possible.getId()).get("saved"));
            assertEquals("NOT_ELIGIBLE", items.get(notEligible.getId()).get("eligibilityStatus"));
            assertEquals("UNKNOWN", items.get(unknown.getId()).get("eligibilityStatus"));
            assertEquals(true, items.get(unknown.getId()).get("saved"));
            for (Opportunity stale : List.of(staleVersion, staleProfile, staleRule, expired, noResult)) {
                assertNull(items.get(stale.getId()).get("eligibilityStatus"));
                assertEquals(false, items.get(stale.getId()).get("saved"));
            }

            MvcResult filtered = mockMvc.perform(get("/api/opportunities")
                            .with(user(principal)).param("keyword", "PG display")
                            .param("eligibilityStatuses", "ELIGIBLE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andReturn();
            assertEquals(List.of(eligible.getId()), opportunityIds(filtered));
            assertEquals("ELIGIBLE", searchItems(filtered).get(eligible.getId()).get("eligibilityStatus"));

            MvcResult other = mockMvc.perform(get("/api/opportunities")
                            .with(user(new CustomUserDetails(otherAccount.getId(), Role.USER, "other")))
                            .param("keyword", "PG display"))
                    .andExpect(status().isOk()).andReturn();
            assertNull(searchItems(other).get(eligible.getId()).get("eligibilityStatus"));
            assertEquals(false, searchItems(other).get(eligible.getId()).get("saved"));
            assertEquals("ELIGIBLE", searchItems(other).get(noResult.getId()).get("eligibilityStatus"));
            assertEquals(true, searchItems(other).get(possible.getId()).get("saved"));

            MvcResult noProfile = mockMvc.perform(get("/api/opportunities")
                            .with(user(new CustomUserDetails(noProfileAccount.getId(), Role.USER, "no-profile")))
                            .param("keyword", "PG display"))
                    .andExpect(status().isOk()).andReturn();
            assertNull(searchItems(noProfile).get(eligible.getId()).get("eligibilityStatus"));
            assertEquals(true, searchItems(noProfile).get(notEligible.getId()).get("saved"));

            long beforeEmptyPublic = statistics.getPrepareStatementCount();
            mockMvc.perform(get("/api/opportunities")
                            .param("keyword", "PG display").param("page", "9").param("size", "2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
            long emptyPublicQueries = statistics.getPrepareStatementCount() - beforeEmptyPublic;
            long beforeEmptyAuthenticated = statistics.getPrepareStatementCount();
            mockMvc.perform(get("/api/opportunities").with(user(principal))
                            .param("keyword", "PG display").param("page", "9").param("size", "2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
            assertEquals(emptyPublicQueries,
                    statistics.getPrepareStatementCount() - beforeEmptyAuthenticated);
        } finally {
            statistics.setStatisticsEnabled(statisticsEnabled);
        }
    }

    @Test
    void regionFilterUsesPostgresJsonAndExactPageCounts() throws Exception {
        Region province = Region.builder().regionCode("1100000000")
                .regionName("Seoul").build();
        entityManager.persist(province);
        Region district = Region.builder().regionCode("1168000000")
                .regionName("Gangnam").parent(province).build();
        entityManager.persist(district);
        Region otherProvince = Region.builder().regionCode("2600000000")
                .regionName("Busan").build();
        entityManager.persist(otherProvince);

        Opportunity national = opportunity("PG region national", OpportunityType.YOUTH_POLICY);
        Opportunity specific = opportunity("PG region specific", OpportunityType.YOUTH_POLICY);
        Opportunity provinceSpecific = opportunity("PG region province", OpportunityType.YOUTH_POLICY);
        Opportunity unknown = opportunity("PG region unknown", OpportunityType.YOUTH_POLICY);
        Opportunity training = opportunity("PG region training", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity elsewhereTraining = opportunity("PG region elsewhere training", OpportunityType.VOCATIONAL_TRAINING);
        national.updateSummary(national.getTitle(), "career", "job", null);
        specific.updateSummary(specific.getTitle(), "career", "job", null);
        provinceSpecific.updateSummary(provinceSpecific.getTitle(), "housing", "support", null);
        unknown.updateSummary(unknown.getTitle(), "career", "job", null);
        condition(national, "NATIONAL");
        condition(unknown, "UNKNOWN");
        condition(elsewhereTraining, "NATIONAL");
        entityManager.persist(OpportunityRegion.builder()
                .version(specific.getCurrentVersion()).region(district)
                .regionRole(RegionRole.RESIDENCE).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(provinceSpecific.getCurrentVersion()).region(province)
                .regionRole(RegionRole.RESIDENCE).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(training.getCurrentVersion()).region(district)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(elsewhereTraining.getCurrentVersion()).region(otherProvince)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        MvcResult firstPage = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", province.getRegionCode())
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn();

        MvcResult secondPage = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", province.getRegionCode())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andReturn();

        List<Long> pagedIds = new ArrayList<>(opportunityIds(firstPage));
        pagedIds.addAll(opportunityIds(secondPage));
        assertEquals(pagedIds.size(), new HashSet<>(pagedIds).size());
        assertEquals(Set.of(national.getId(), specific.getId(), provinceSpecific.getId(), training.getId()),
                new HashSet<>(pagedIds));
        assertFalse(pagedIds.contains(unknown.getId()));
        assertFalse(pagedIds.contains(elsewhereTraining.getId()));

        MvcResult districtResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", district.getRegionCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andReturn();
        assertEquals(Set.of(national.getId(), specific.getId(), provinceSpecific.getId(), training.getId()),
                new HashSet<>(opportunityIds(districtResult)));

        MvcResult trainingResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", province.getRegionCode())
                        .param("type", "VOCATIONAL_TRAINING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(training.getId()), opportunityIds(trainingResult));

        MvcResult elsewhereResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", otherProvince.getRegionCode())
                        .param("type", "VOCATIONAL_TRAINING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(elsewhereTraining.getId()), opportunityIds(elsewhereResult));

        MvcResult combinedResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", district.getRegionCode())
                        .param("type", "YOUTH_POLICY")
                        .param("category", "career")
                        .param("subCategory", "job"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        assertEquals(Set.of(national.getId(), specific.getId()),
                new HashSet<>(opportunityIds(combinedResult)));

        MvcResult parentResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", district.getRegionCode())
                        .param("type", "YOUTH_POLICY")
                        .param("category", "housing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(provinceSpecific.getId()), opportunityIds(parentResult));
    }

    @Test
    void applicationStatusUsesPostgresWithMultipleWindowsAndExactPageCounts() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Region province = Region.builder().regionCode("1100000000")
                .regionName("Seoul").build();
        entityManager.persist(province);

        Opportunity policy = opportunity("PG status open policy", OpportunityType.YOUTH_POLICY);
        Opportunity training = opportunity("PG status open training", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity upcoming = opportunity("PG status upcoming policy", OpportunityType.YOUTH_POLICY);
        Opportunity always = opportunity("PG status always policy", OpportunityType.YOUTH_POLICY);
        Opportunity closed = opportunity("PG status closed policy", OpportunityType.YOUTH_POLICY);
        Opportunity unknown = opportunity("PG status unknown training", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity futureTraining = opportunity("PG status future training", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity startedTraining = opportunity("PG status started training", OpportunityType.VOCATIONAL_TRAINING);
        policy.updateSummary(policy.getTitle(), "career", "job", null);
        training.updateSummary(training.getTitle(), "career", "course", null);
        futureTraining.updateSummary(futureTraining.getTitle(), "career", "course", null);
        entityManager.persist(OpportunityRegion.builder()
                .version(policy.getCurrentVersion()).region(province)
                .regionRole(RegionRole.RESIDENCE).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(training.getCurrentVersion()).region(province)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(futureTraining.getCurrentVersion()).region(province)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(startedTraining.getCurrentVersion()).region(province)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        window(policy, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.VALID);
        window(policy, PeriodType.ALWAYS, null, null, ValidationStatus.VALID);
        window(policy, PeriodType.FIXED, today.minusDays(10), today.minusDays(5), ValidationStatus.VALID);
        window(training, PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN);
        window(upcoming, PeriodType.FIXED, today.plusDays(1), today.plusDays(10), ValidationStatus.VALID);
        window(always, PeriodType.ALWAYS, null, null, ValidationStatus.VALID);
        window(closed, PeriodType.CLOSED, null, null, ValidationStatus.VALID);
        window(unknown, PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN);
        window(futureTraining, PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN);
        window(startedTraining, PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN);
        trainingDetail(training, today);
        trainingDetail(unknown, null);
        trainingDetail(futureTraining, today.plusDays(1));
        trainingDetail(startedTraining, today.minusDays(1));
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        MvcResult unfiltered = mockMvc.perform(get("/api/opportunities").param("keyword", "PG status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(8))
                .andReturn();
        assertEquals(Set.of(policy.getId(), training.getId(), upcoming.getId(), always.getId(),
                closed.getId(), unknown.getId(), futureTraining.getId(), startedTraining.getId()),
                new HashSet<>(opportunityIds(unfiltered)));

        List<Long> openIds = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            MvcResult result = mockMvc.perform(get("/api/opportunities")
                            .param("keyword", "PG status")
                            .param("applicationStatus", "OPEN")
                            .param("page", String.valueOf(page))
                            .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andReturn();
            openIds.addAll(opportunityIds(result));
        }
        assertEquals(Set.of(policy.getId(), training.getId(), futureTraining.getId()), new HashSet<>(openIds));
        assertEquals(openIds.size(), new HashSet<>(openIds).size());

        for (Object[] expected : new Object[][]{
                {"ALWAYS_OPEN", always.getId()}, {"UPCOMING", upcoming.getId()},
                {"CLOSED", closed.getId()}
        }) {
            MvcResult result = mockMvc.perform(get("/api/opportunities")
                            .param("keyword", "PG status")
                            .param("applicationStatus", (String) expected[0]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andReturn();
            assertEquals(List.of(expected[1]), opportunityIds(result));
        }

        MvcResult unknownResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG status")
                        .param("applicationStatus", "UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andReturn();
        assertEquals(Set.of(training.getId(), unknown.getId(), futureTraining.getId(), startedTraining.getId()),
                new HashSet<>(opportunityIds(unknownResult)));

        MvcResult combined = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG status")
                        .param("applicationStatus", "OPEN")
                        .param("type", "YOUTH_POLICY")
                        .param("category", "career")
                        .param("region", province.getRegionCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(policy.getId()), opportunityIds(combined));

        MvcResult trainingResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG status")
                        .param("applicationStatus", "OPEN")
                        .param("type", "VOCATIONAL_TRAINING")
                        .param("category", "career")
                        .param("region", province.getRegionCode())
                        .param("sort", "START_DATE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        assertEquals(List.of(training.getId(), futureTraining.getId()), opportunityIds(trainingResult));

        MvcResult todayTraining = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG status")
                        .param("applicationStatus", "OPEN")
                        .param("type", "VOCATIONAL_TRAINING")
                        .param("startDateTo", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(training.getId()), opportunityIds(todayTraining));
    }

    @Test
    void dateRangesUsePostgresWithExactCountsAndExistingFilters() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Region province = Region.builder().regionCode("1100000000")
                .regionName("Seoul").build();
        entityManager.persist(province);
        Opportunity earlyPolicy = opportunity("PG date early policy", OpportunityType.YOUTH_POLICY);
        Opportunity latePolicy = opportunity("PG date late policy", OpportunityType.YOUTH_POLICY);
        Opportunity alwaysPolicy = opportunity("PG date always policy", OpportunityType.YOUTH_POLICY);
        Opportunity earlyTraining = opportunity("PG date early training", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity lateTraining = opportunity("PG date late training", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity unknownTraining = opportunity("PG date unknown training", OpportunityType.VOCATIONAL_TRAINING);
        earlyPolicy.updateSummary(earlyPolicy.getTitle(), "career", "job", null);
        earlyTraining.updateSummary(earlyTraining.getTitle(), "career", "course", null);
        entityManager.persist(OpportunityRegion.builder()
                .version(earlyPolicy.getCurrentVersion()).region(province)
                .regionRole(RegionRole.RESIDENCE).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(earlyTraining.getCurrentVersion()).region(province)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        window(earlyPolicy, PeriodType.FIXED, today.minusDays(1), today.plusDays(3), ValidationStatus.VALID);
        window(earlyPolicy, PeriodType.FIXED, today.minusDays(1), today.plusDays(3), ValidationStatus.VALID);
        window(latePolicy, PeriodType.FIXED, today.minusDays(1), today.plusDays(5), ValidationStatus.VALID);
        window(alwaysPolicy, PeriodType.ALWAYS, null, null, ValidationStatus.VALID);
        window(earlyTraining, PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN);
        trainingDetail(earlyTraining, today.plusDays(2));
        trainingDetail(lateTraining, today.plusDays(4));
        trainingDetail(unknownTraining, null);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities").param("keyword", "PG date"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6));

        List<Long> policyIds = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            MvcResult result = mockMvc.perform(get("/api/opportunities")
                            .param("keyword", "PG date").param("type", "YOUTH_POLICY")
                            .param("deadlineFrom", today.plusDays(3).toString())
                            .param("page", String.valueOf(page)).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andReturn();
            policyIds.addAll(opportunityIds(result));
        }
        assertEquals(Set.of(earlyPolicy.getId(), latePolicy.getId()), new HashSet<>(policyIds));
        assertEquals(policyIds.size(), new HashSet<>(policyIds).size());

        MvcResult policyResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG date").param("type", "YOUTH_POLICY")
                        .param("deadlineTo", today.plusDays(3).toString())
                        .param("applicationStatus", "OPEN")
                        .param("region", province.getRegionCode())
                        .param("category", "career"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(earlyPolicy.getId()), opportunityIds(policyResult));

        MvcResult lateResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG date").param("type", "VOCATIONAL_TRAINING")
                        .param("startDateFrom", today.plusDays(4).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(lateTraining.getId()), opportunityIds(lateResult));

        MvcResult trainingResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG date").param("type", "VOCATIONAL_TRAINING")
                        .param("startDateFrom", today.plusDays(2).toString())
                        .param("startDateTo", today.plusDays(2).toString())
                        .param("applicationStatus", "OPEN")
                        .param("region", province.getRegionCode())
                        .param("category", "career"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(earlyTraining.getId()), opportunityIds(trainingResult));

        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG date").param("type", "VOCATIONAL_TRAINING")
                        .param("startDateTo", today.plusDays(2).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(earlyTraining.getId()));
    }

    @Test
    void sortOptionsUsePostgresBeforePaginationAndKeepExactTotals() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Region province = Region.builder().regionCode("1100000000")
                .regionName("Seoul").build();
        entityManager.persist(province);
        Opportunity near = opportunity("PG sort deadline near", OpportunityType.YOUTH_POLICY);
        Opportunity multi = opportunity("PG sort deadline multi", OpportunityType.YOUTH_POLICY);
        Opportunity unknown = opportunity("PG sort deadline unknown", OpportunityType.YOUTH_POLICY);
        Opportunity upcoming = opportunity("PG sort deadline upcoming", OpportunityType.YOUTH_POLICY);
        near.updateSummary(near.getTitle(), "career", "job", null);
        multi.updateSummary(multi.getTitle(), "career", "job", null);
        for (Opportunity policy : new Opportunity[]{near, multi}) {
            entityManager.persist(OpportunityRegion.builder()
                    .version(policy.getCurrentVersion()).region(province)
                    .regionRole(RegionRole.RESIDENCE).build());
        }
        window(near, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.VALID);
        window(multi, PeriodType.FIXED, today.minusDays(1), today.plusDays(5), ValidationStatus.VALID);
        window(multi, PeriodType.FIXED, today.minusDays(1), today.plusDays(2), ValidationStatus.VALID);
        window(upcoming, PeriodType.FIXED, today.plusDays(1), today.plusDays(3), ValidationStatus.VALID);
        window(unknown, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.VALID);
        OpportunityVersion replacement = OpportunityVersion.builder()
                .opportunity(unknown).versionNo(2)
                .normalizedSnapshot(Map.of("title", unknown.getTitle()))
                .personalizationStatus(PersonalizationStatus.UNKNOWN)
                .normalizationVersion("1").build();
        entityManager.persist(replacement);
        unknown.changeCurrentVersion(replacement);

        Opportunity trainingNear = opportunity("PG sort start near", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity trainingLate = opportunity("PG sort start late", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity trainingUnknown = opportunity("PG sort start unknown", OpportunityType.VOCATIONAL_TRAINING);
        trainingNear.updateSummary(trainingNear.getTitle(), "career", "course", null);
        entityManager.persist(OpportunityRegion.builder()
                .version(trainingNear.getCurrentVersion()).region(province)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        window(trainingNear, PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN);
        trainingDetail(trainingNear, today.plusDays(1));
        trainingDetail(trainingLate, today.plusDays(3));
        trainingDetail(trainingUnknown, null);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        MvcResult latest = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG sort deadline").param("type", "YOUTH_POLICY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andReturn();
        assertEquals(List.of(upcoming.getId(), unknown.getId(), multi.getId(), near.getId()),
                opportunityIds(latest));
        MvcResult oldest = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG sort deadline").param("type", "YOUTH_POLICY")
                        .param("sort", "OLDEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andReturn();
        assertEquals(List.of(near.getId(), multi.getId(), unknown.getId(), upcoming.getId()),
                opportunityIds(oldest));

        List<Long> deadlineIds = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            MvcResult result = mockMvc.perform(get("/api/opportunities")
                            .param("keyword", "PG sort deadline")
                            .param("type", "YOUTH_POLICY").param("sort", "DEADLINE_ASC")
                            .param("page", String.valueOf(page)).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.totalPages").value(4))
                    .andReturn();
            deadlineIds.addAll(opportunityIds(result));
        }
        assertEquals(List.of(near.getId(), multi.getId(), upcoming.getId(), unknown.getId()), deadlineIds);
        assertEquals(deadlineIds.size(), new HashSet<>(deadlineIds).size());

        MvcResult combinedPolicy = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG sort deadline").param("type", "YOUTH_POLICY")
                        .param("sort", "DEADLINE_ASC")
                        .param("category", "career").param("region", province.getRegionCode())
                        .param("applicationStatus", "OPEN")
                        .param("deadlineFrom", today.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        assertEquals(List.of(near.getId(), multi.getId()), opportunityIds(combinedPolicy));

        MvcResult training = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG sort start")
                        .param("type", "VOCATIONAL_TRAINING").param("sort", "START_DATE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();
        assertEquals(List.of(trainingNear.getId(), trainingLate.getId(), trainingUnknown.getId()),
                opportunityIds(training));
        MvcResult combinedTraining = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG sort start")
                        .param("type", "VOCATIONAL_TRAINING").param("sort", "START_DATE_ASC")
                        .param("category", "career").param("region", province.getRegionCode())
                        .param("applicationStatus", "OPEN")
                        .param("startDateTo", today.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(trainingNear.getId()), opportunityIds(combinedTraining));
    }

    private static List<Long> opportunityIds(MvcResult result) throws Exception {
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(),
                "$.content[*].opportunityId");
        return ids.stream().map(Number::longValue).toList();
    }

    private static Map<Long, Map<String, Object>> searchItems(MvcResult result) throws Exception {
        List<Map<String, Object>> content = JsonPath.read(result.getResponse().getContentAsString(), "$.content");
        Map<Long, Map<String, Object>> items = new HashMap<>();
        for (Map<String, Object> item : content) {
            items.put(((Number) item.get("opportunityId")).longValue(), item);
        }
        return items;
    }

    private Opportunity opportunity(String title, OpportunityType type) {
        Opportunity opportunity = Opportunity.builder().title(title)
                .opportunityType(type).build();
        entityManager.persist(opportunity);
        OpportunityVersion version = OpportunityVersion.builder()
                .opportunity(opportunity).versionNo(1)
                .normalizedSnapshot(Map.of("title", title))
                .personalizationStatus(PersonalizationStatus.UNKNOWN)
                .normalizationVersion("1").build();
        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
        return opportunity;
    }

    private void condition(Opportunity opportunity, String regionStatus) {
        entityManager.persist(OpportunityCondition.builder()
                .version(opportunity.getCurrentVersion())
                .conditionType(ConditionType.REGION)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("residenceRegion")
                .expression(Map.of("status", regionStatus, "role", "RESIDENCE"))
                .validationStatus(ValidationStatus.VALID)
                .build());
    }

    private void window(Opportunity opportunity, PeriodType type, LocalDate start, LocalDate end,
                        ValidationStatus validationStatus) {
        entityManager.persist(ApplicationWindow.builder()
                .version(opportunity.getCurrentVersion())
                .periodType(type).startDate(start).endDate(end)
                .validationStatus(validationStatus).build());
    }

    private void trainingDetail(Opportunity opportunity, LocalDate startDate) {
        entityManager.persist(TrainingCourseDetail.builder()
                .version(opportunity.getCurrentVersion())
                .courseId("PG-date-" + opportunity.getId()).courseRound(1)
                .trainingStartDate(startDate).build());
    }
}
