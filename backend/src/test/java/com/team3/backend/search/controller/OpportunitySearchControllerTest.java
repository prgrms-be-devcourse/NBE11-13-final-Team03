package com.team3.backend.search.controller;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
}
