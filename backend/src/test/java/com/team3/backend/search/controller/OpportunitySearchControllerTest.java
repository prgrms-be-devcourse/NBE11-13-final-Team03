package com.team3.backend.search.controller;

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
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpportunitySearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RegionDirectory regionDirectory;

    @BeforeEach
    void registerJsonFunction() {
        entityManager.createNativeQuery("CREATE ALIAS IF NOT EXISTS JSONB_EXTRACT_PATH_TEXT FOR "
                + "\"com.team3.backend.search.controller.H2JsonFunctions.extract\"")
                .executeUpdate();
    }

    @AfterEach
    void clearRegionIndex() {
        regionDirectory.refresh();
    }

    @Test
    void policyRegionMatchesHierarchyAndNationalButNotUnknown() throws Exception {
        Region province = createRegion("1100000000", null);
        Region district = createRegion("1168000000", province);
        Region otherProvince = createRegion("2600000000", null);
        Opportunity specificDistrict = createOpportunity("V1-2 policy district", OpportunityType.YOUTH_POLICY,
                "career", "job", true);
        Opportunity specificProvince = createOpportunity("V1-2 policy province", OpportunityType.YOUTH_POLICY,
                "career", "job", true);
        Opportunity national = createOpportunity("V1-2 policy national", OpportunityType.YOUTH_POLICY,
                "career", "job", true);
        Opportunity unknown = createOpportunity("V1-2 policy unknown", OpportunityType.YOUTH_POLICY,
                "career", "job", true);
        Opportunity other = createOpportunity("V1-2 policy other", OpportunityType.YOUTH_POLICY,
                "career", "job", true);
        addRegion(specificDistrict, district, RegionRole.RESIDENCE);
        addRegion(specificProvince, province, RegionRole.RESIDENCE);
        addPolicyRegionCondition(national, "NATIONAL");
        addPolicyRegionCondition(unknown, "UNKNOWN");
        addRegion(other, otherProvince, RegionRole.RESIDENCE);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities")
                        .param("region", province.getRegionCode())
                        .param("keyword", "V1-2 policy")
                        .param("type", "YOUTH_POLICY")
                        .param("category", "career")
                        .param("subCategory", "job"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/opportunities")
                        .param("region", district.getRegionCode())
                        .param("keyword", "V1-2 policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/opportunities")
                        .param("region", otherProvince.getRegionCode())
                        .param("keyword", "V1-2 policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/opportunities")
                        .param("region", province.getRegionCode())
                        .param("keyword", "V1-2 policy")
                        .param("category", "housing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/opportunities").param("keyword", "V1-2 policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void trainingUsesImplementationOnlyAndDoesNotTreatNationalAsTrainingLocation() throws Exception {
        Region province = createRegion("1100000000", null);
        Region district = createRegion("1168000000", province);
        Region otherProvince = createRegion("2600000000", null);
        Opportunity localTraining = createOpportunity("V1-2 training local", OpportunityType.VOCATIONAL_TRAINING,
                null, null, true);
        Opportunity elsewhereTraining = createOpportunity("V1-2 training elsewhere", OpportunityType.VOCATIONAL_TRAINING,
                null, null, true);
        Opportunity unknownTraining = createOpportunity("V1-2 training unknown", OpportunityType.VOCATIONAL_TRAINING,
                null, null, true);
        addRegion(localTraining, district, RegionRole.IMPLEMENTATION);
        addRegion(elsewhereTraining, otherProvince, RegionRole.IMPLEMENTATION);
        addRegion(elsewhereTraining, district, RegionRole.RESIDENCE);
        addPolicyRegionCondition(elsewhereTraining, "NATIONAL");
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities")
                        .param("region", province.getRegionCode())
                        .param("keyword", "V1-2 training"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(localTraining.getId()))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/opportunities")
                        .param("region", otherProvince.getRegionCode())
                        .param("keyword", "V1-2 training"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].opportunityId").value(elsewhereTraining.getId()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void regionFilterUsesOnlyCurrentVersionAndCountsEachOpportunityOnce() throws Exception {
        Region province = createRegion("1100000000", null);
        Region firstDistrict = createRegion("1168000000", province);
        Region secondDistrict = createRegion("1171000000", province);
        Opportunity current = createOpportunity("V1-2 version current", OpportunityType.YOUTH_POLICY,
                null, null, true);
        Opportunity former = createOpportunity("V1-2 version former", OpportunityType.YOUTH_POLICY,
                null, null, true);
        Opportunity another = createOpportunity("V1-2 version another", OpportunityType.YOUTH_POLICY,
                null, null, true);
        addRegion(current, firstDistrict, RegionRole.RESIDENCE);
        addRegion(current, secondDistrict, RegionRole.RESIDENCE);
        addPolicyRegionCondition(current, "NATIONAL");
        addRegion(former, firstDistrict, RegionRole.RESIDENCE);
        addRegion(another, province, RegionRole.RESIDENCE);
        OpportunityVersion replacement = OpportunityVersion.builder()
                .opportunity(former).versionNo(2)
                .normalizedSnapshot(Map.of("title", former.getTitle()))
                .personalizationStatus(PersonalizationStatus.UNKNOWN)
                .normalizationVersion("1").build();
        entityManager.persist(replacement);
        former.changeCurrentVersion(replacement);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities")
                        .param("region", province.getRegionCode())
                        .param("keyword", "V1-2 version")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(another.getId()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/opportunities")
                        .param("region", province.getRegionCode())
                        .param("keyword", "V1-2 version")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].opportunityId").value(current.getId()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void invalidRegionReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/opportunities").param("region", "9999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void applicationStatusUsesCommonRulesAndCombinesWithExistingFilters() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Region province = createRegion("1100000000", null);
        Opportunity open = createOpportunity("V1-3 open policy", OpportunityType.YOUTH_POLICY,
                "career", "job", true);
        Opportunity always = createOpportunity("V1-3 always policy", OpportunityType.YOUTH_POLICY,
                "housing", "support", true);
        Opportunity upcoming = createOpportunity("V1-3 upcoming training", OpportunityType.VOCATIONAL_TRAINING,
                "career", "course", true);
        Opportunity closed = createOpportunity("V1-3 closed policy", OpportunityType.YOUTH_POLICY,
                "career", "job", true);
        Opportunity unknown = createOpportunity("V1-3 unknown training", OpportunityType.VOCATIONAL_TRAINING,
                "career", "course", true);
        Opportunity openTraining = createOpportunity("V1-3 open training", OpportunityType.VOCATIONAL_TRAINING,
                "career", "course", true);
        addRegion(open, province, RegionRole.RESIDENCE);
        addRegion(openTraining, province, RegionRole.IMPLEMENTATION);
        addWindow(open, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.VALID);
        addWindow(open, PeriodType.ALWAYS, null, null, ValidationStatus.VALID);
        addWindow(open, PeriodType.FIXED, today.minusDays(10), today.minusDays(5), ValidationStatus.VALID);
        addWindow(always, PeriodType.ALWAYS, null, null, ValidationStatus.VALID);
        addWindow(upcoming, PeriodType.FIXED, today.plusDays(1), today.plusDays(10), ValidationStatus.VALID);
        addWindow(closed, PeriodType.CLOSED, null, null, ValidationStatus.VALID);
        addWindow(unknown, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.INVALID);
        addWindow(openTraining, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.VALID);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities").param("keyword", "V1-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6));

        for (Object[] expected : new Object[][]{
                {ApplicationStatus.ALWAYS_OPEN, always.getId()},
                {ApplicationStatus.UPCOMING, upcoming.getId()},
                {ApplicationStatus.CLOSED, closed.getId()},
                {ApplicationStatus.UNKNOWN, unknown.getId()}
        }) {
            mockMvc.perform(get("/api/opportunities")
                            .param("keyword", "V1-3")
                            .param("applicationStatus", ((ApplicationStatus) expected[0]).name()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].opportunityId").value(expected[1]));
        }

        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-3").param("applicationStatus", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-3")
                        .param("applicationStatus", "OPEN")
                        .param("type", "YOUTH_POLICY")
                        .param("category", "career")
                        .param("subCategory", "job")
                        .param("region", province.getRegionCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(open.getId()));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-3")
                        .param("applicationStatus", "OPEN")
                        .param("type", "VOCATIONAL_TRAINING")
                        .param("region", province.getRegionCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(openTraining.getId()));
        mockMvc.perform(get("/api/opportunities").param("applicationStatus", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dateRangesUsePolicyDeadlinesAndTrainingStartDatesWithoutChangingUnfilteredSearch() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Region province = createRegion("1100000000", null);
        Opportunity earlyPolicy = createOpportunity("V1-4 early policy", OpportunityType.YOUTH_POLICY,
                "career", "job", true);
        Opportunity latePolicy = createOpportunity("V1-4 late policy", OpportunityType.YOUTH_POLICY,
                "housing", "support", true);
        Opportunity alwaysPolicy = createOpportunity("V1-4 always policy", OpportunityType.YOUTH_POLICY,
                null, null, true);
        Opportunity unknownPolicy = createOpportunity("V1-4 unknown policy", OpportunityType.YOUTH_POLICY,
                null, null, true);
        Opportunity earlyTraining = createOpportunity("V1-4 early training", OpportunityType.VOCATIONAL_TRAINING,
                "career", "course", true);
        Opportunity lateTraining = createOpportunity("V1-4 late training", OpportunityType.VOCATIONAL_TRAINING,
                null, null, true);
        Opportunity unknownTraining = createOpportunity("V1-4 unknown training", OpportunityType.VOCATIONAL_TRAINING,
                null, null, true);
        addRegion(earlyPolicy, province, RegionRole.RESIDENCE);
        addRegion(earlyTraining, province, RegionRole.IMPLEMENTATION);
        addWindow(earlyPolicy, PeriodType.FIXED, today.minusDays(1), today.plusDays(3), ValidationStatus.VALID);
        addWindow(earlyPolicy, PeriodType.FIXED, today.minusDays(1), today.plusDays(3), ValidationStatus.VALID);
        addWindow(latePolicy, PeriodType.FIXED, today.minusDays(1), today.plusDays(5), ValidationStatus.VALID);
        addWindow(alwaysPolicy, PeriodType.ALWAYS, null, null, ValidationStatus.VALID);
        addWindow(unknownPolicy, PeriodType.FIXED, today.minusDays(1), today.plusDays(3), ValidationStatus.INVALID);
        addWindow(unknownPolicy, PeriodType.FIXED, today.minusDays(1), null, ValidationStatus.VALID);
        addWindow(earlyTraining, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.VALID);
        addTrainingDetail(earlyTraining, today.plusDays(2));
        addTrainingDetail(lateTraining, today.plusDays(4));
        addTrainingDetail(unknownTraining, null);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities").param("keyword", "V1-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7));

        for (int page = 0; page < 2; page++) {
            mockMvc.perform(get("/api/opportunities")
                            .param("keyword", "V1-4")
                            .param("type", "YOUTH_POLICY")
                            .param("deadlineFrom", today.plusDays(3).toString())
                            .param("page", String.valueOf(page)).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.content[0].opportunityId")
                            .value(page == 0 ? latePolicy.getId() : earlyPolicy.getId()));
        }
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-4").param("type", "YOUTH_POLICY")
                        .param("deadlineTo", today.plusDays(3).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(earlyPolicy.getId()));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-4").param("type", "YOUTH_POLICY")
                        .param("deadlineFrom", today.plusDays(3).toString())
                        .param("deadlineTo", today.plusDays(3).toString())
                        .param("applicationStatus", "OPEN")
                        .param("region", province.getRegionCode())
                        .param("category", "career"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(earlyPolicy.getId()));

        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-4").param("type", "VOCATIONAL_TRAINING")
                        .param("startDateFrom", today.plusDays(4).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(lateTraining.getId()));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-4").param("type", "VOCATIONAL_TRAINING")
                        .param("startDateTo", today.plusDays(2).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(earlyTraining.getId()));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-4").param("type", "VOCATIONAL_TRAINING")
                        .param("startDateFrom", today.plusDays(2).toString())
                        .param("startDateTo", today.plusDays(2).toString())
                        .param("applicationStatus", "OPEN")
                        .param("region", province.getRegionCode())
                        .param("category", "career"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(earlyTraining.getId()));

        for (String[] invalid : new String[][]{
                {"YOUTH_POLICY", "deadlineFrom", today.plusDays(5).toString(),
                        "deadlineTo", today.plusDays(3).toString()},
                {"VOCATIONAL_TRAINING", "startDateFrom", today.plusDays(4).toString(),
                        "startDateTo", today.plusDays(2).toString()}
        }) {
            mockMvc.perform(get("/api/opportunities")
                            .param("type", invalid[0])
                            .param(invalid[1], invalid[2]).param(invalid[3], invalid[4]))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/opportunities").param("deadlineFrom", today.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/opportunities")
                        .param("type", "VOCATIONAL_TRAINING").param("deadlineTo", today.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/opportunities")
                        .param("type", "YOUTH_POLICY").param("startDateFrom", today.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/opportunities")
                        .param("type", "YOUTH_POLICY").param("deadlineFrom", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousSearchCombinesFiltersAndExcludesUnnormalizedOpportunities() throws Exception {
        Opportunity match = createOpportunity("V1-1 training policy", OpportunityType.YOUTH_POLICY,
                "employment", "training", true);
        createOpportunity("V1-1 training policy", OpportunityType.VOCATIONAL_TRAINING,
                "employment", "training", true);
        createOpportunity("V1-1 training policy", OpportunityType.YOUTH_POLICY,
                "housing", "training", true);
        createOpportunity("V1-1 training policy", OpportunityType.YOUTH_POLICY,
                "employment", "training", false);

        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "TRAINING")
                        .param("type", "YOUTH_POLICY")
                        .param("category", "employment")
                        .param("subCategory", "training"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId").value(match.getId()))
                .andExpect(jsonPath("$.content[0].type").value("YOUTH_POLICY"))
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void filtersAreOptionalAndHiddenOpportunitiesRemainVisible() throws Exception {
        Opportunity hidden = createOpportunity("V1-1 hidden listing", OpportunityType.YOUTH_POLICY,
                "employment", "internship", true);
        User user = User.builder().kakaoId(899001L).role(Role.USER).build();
        entityManager.persist(user);
        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(user).opportunity(hidden).build();
        preference.markHidden();
        entityManager.persist(preference);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/opportunities").param("keyword", "V1-1 hidden listing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].opportunityId").value(hidden.getId()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void individualFiltersAndLiteralKeywordAreAppliedInDatabase() throws Exception {
        createOpportunity("V1-1 percent % item", OpportunityType.YOUTH_POLICY,
                "career", "internship", true);
        createOpportunity("V1-1 percent plain item", OpportunityType.VOCATIONAL_TRAINING,
                "career", "course", true);
        createOpportunity("V1-1 other item", OpportunityType.YOUTH_POLICY,
                "housing", "internship", true);

        mockMvc.perform(get("/api/opportunities").param("keyword", "percent %"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-1")
                        .param("type", "VOCATIONAL_TRAINING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-1")
                        .param("category", "career"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-1")
                        .param("subCategory", "internship"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-1")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void pagesUseCreatedAtThenIdAndReportExactTotals() throws Exception {
        Opportunity first = createOpportunity("V1-1 paging first", OpportunityType.YOUTH_POLICY,
                null, null, true);
        Opportunity second = createOpportunity("V1-1 paging second", OpportunityType.YOUTH_POLICY,
                null, null, true);
        Opportunity newest = createOpportunity("V1-1 paging newest", OpportunityType.YOUTH_POLICY,
                null, null, true);
        entityManager.flush();
        LocalDateTime older = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime tied = LocalDateTime.of(2025, 2, 1, 0, 0);
        setCreatedAt(first, older);
        setCreatedAt(second, tied);
        setCreatedAt(newest, tied);
        entityManager.clear();

        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-1 paging")
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].opportunityId").value(newest.getId()))
                .andExpect(jsonPath("$.content[1].opportunityId").value(second.getId()))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-1 paging")
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].opportunityId").value(first.getId()))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "V1-1 paging")
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void emptyResultsAndInvalidParameters() throws Exception {
        mockMvc.perform(get("/api/opportunities").param("keyword", "V1-1 no matching listing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));

        for (String[] invalid : new String[][]{
                {"page", "-1"}, {"size", "0"}, {"size", "101"}
        }) {
            mockMvc.perform(get("/api/opportunities").param(invalid[0], invalid[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"));
        }
        mockMvc.perform(get("/api/opportunities").param("type", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
        mockMvc.perform(get("/api/opportunities").param("page", "text"))
                .andExpect(status().isBadRequest());
    }

    private Opportunity createOpportunity(String title, OpportunityType type,
                                          String category, String subCategory,
                                          boolean normalized) {
        Opportunity opportunity = Opportunity.builder()
                .title(title).opportunityType(type)
                .category(category).subCategory(subCategory).build();
        entityManager.persist(opportunity);
        if (normalized) {
            OpportunityVersion version = OpportunityVersion.builder()
                    .opportunity(opportunity).versionNo(1)
                    .normalizedSnapshot(Map.of("title", title))
                    .personalizationStatus(PersonalizationStatus.UNKNOWN)
                    .normalizationVersion("1").build();
            entityManager.persist(version);
            opportunity.changeCurrentVersion(version);
        }
        entityManager.flush();
        return opportunity;
    }

    private void setCreatedAt(Opportunity opportunity, LocalDateTime createdAt) {
        entityManager.createQuery("update Opportunity o set o.createdAt = :createdAt where o.id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", opportunity.getId())
                .executeUpdate();
    }

    private Region createRegion(String code, Region parent) {
        Region region = Region.builder().regionCode(code).regionName(code).parent(parent).build();
        entityManager.persist(region);
        return region;
    }

    private void addRegion(Opportunity opportunity, Region region, RegionRole role) {
        entityManager.persist(OpportunityRegion.builder()
                .version(opportunity.getCurrentVersion())
                .region(region).regionRole(role).build());
    }

    private void addWindow(Opportunity opportunity, PeriodType type, LocalDate start, LocalDate end,
                           ValidationStatus validationStatus) {
        entityManager.persist(ApplicationWindow.builder()
                .version(opportunity.getCurrentVersion())
                .periodType(type).startDate(start).endDate(end)
                .validationStatus(validationStatus).build());
    }

    private void addTrainingDetail(Opportunity opportunity, LocalDate startDate) {
        entityManager.persist(TrainingCourseDetail.builder()
                .version(opportunity.getCurrentVersion())
                .courseId("V1-4-" + opportunity.getId()).courseRound(1)
                .trainingStartDate(startDate).build());
    }

    private void addPolicyRegionCondition(Opportunity opportunity, String status) {
        entityManager.persist(OpportunityCondition.builder()
                .version(opportunity.getCurrentVersion())
                .conditionType(ConditionType.REGION)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("residenceRegion")
                .expression(Map.of("status", status, "role", "RESIDENCE"))
                .validationStatus(ValidationStatus.VALID)
                .build());
    }
}
