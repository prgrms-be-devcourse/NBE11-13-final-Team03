package com.team3.backend.preference.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class PreferenceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOpportunityPreferenceRepository preferenceRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void changeEndpointsReturnNoContentAndKeepStatesIndependent() throws Exception {
        Long userId = createUser(301L);
        Long opportunityId = createOpportunity("Controller state");
        CustomUserDetails principal = principal(userId);

        mockMvc.perform(post("/api/opportunities/{id}/save", opportunityId)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/opportunities/{id}/hide", opportunityId)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isNoContent());

        UserOpportunityPreference both = findPreference(userId, opportunityId);
        assertTrue(both.isSaved());
        assertTrue(both.isHidden());
        var savedAt = both.getSavedAt();
        var hiddenAt = both.getHiddenAt();

        mockMvc.perform(post("/api/opportunities/{id}/save", opportunityId)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/opportunities/{id}/hide", opportunityId)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isNoContent());
        assertEquals(savedAt, findPreference(userId, opportunityId).getSavedAt());
        assertEquals(hiddenAt, findPreference(userId, opportunityId).getHiddenAt());

        mockMvc.perform(delete("/api/opportunities/{id}/save", opportunityId)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isNoContent());
        UserOpportunityPreference hidden = findPreference(userId, opportunityId);
        assertFalse(hidden.isSaved());
        assertTrue(hidden.isHidden());
        assertEquals(hiddenAt, hidden.getHiddenAt());

        mockMvc.perform(delete("/api/opportunities/{id}/hide", opportunityId)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isNoContent());
        assertTrue(preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId).isEmpty());
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        mockMvc.perform(post("/api/opportunities/1/save").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/opportunities/1/save").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/opportunities/1/hide").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/opportunities/1/hide").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/saved-opportunities"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingOpportunityReturnsNotFound() throws Exception {
        Long userId = createUser(302L);

        mockMvc.perform(post("/api/opportunities/{id}/save", Long.MAX_VALUE)
                        .with(user(principal(userId))).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PREFERENCE_001"));
    }

    @Test
    void savedListReturnsOnlyCurrentUsersSavedOpportunities() throws Exception {
        Long userId = createUser(303L);
        Long otherUserId = createUser(304L);
        Long opportunityId = createOpportunity("My opportunity");
        Long otherOpportunityId = createOpportunity("Other opportunity");

        mockMvc.perform(post("/api/opportunities/{id}/save", opportunityId)
                        .with(user(principal(userId))).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/opportunities/{id}/hide", opportunityId)
                        .with(user(principal(userId))).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/opportunities/{id}/save", otherOpportunityId)
                        .with(user(principal(otherUserId))).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me/saved-opportunities")
                        .with(user(principal(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].opportunityId")
                        .value(opportunityId))
                .andExpect(jsonPath("$.content[0].type")
                        .value("YOUTH_POLICY"))
                .andExpect(jsonPath("$.content[0].title")
                        .value("My opportunity"))
                .andExpect(jsonPath("$.content[0].category")
                        .value("Category"))
                .andExpect(jsonPath("$.content[0].subCategory")
                        .value("Subcategory"))
                .andExpect(jsonPath("$.content[0].savedAt").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(get("/api/users/me/saved-opportunities")
                        .param("page", "1")
                        .param("size", "1")
                        .with(user(principal(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void savedListReturnsEmptyPageAndRejectsInvalidPagination() throws Exception {
        Long userId = createUser(305L);

        mockMvc.perform(get("/api/users/me/saved-opportunities")
                        .with(user(principal(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));

        for (String query : new String[]{"?page=-1", "?size=0", "?size=101"}) {
            mockMvc.perform(get("/api/users/me/saved-opportunities" + query)
                            .with(user(principal(userId))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"));
        }
    }

    private Long createUser(Long kakaoId) {
        User user = userRepository.saveAndFlush(User.builder()
                .kakaoId(kakaoId)
                .role(Role.USER)
                .build());
        return user.getId();
    }

    private Long createOpportunity(String title) {
        return transactionTemplate.execute(status -> {
            Opportunity opportunity = Opportunity.builder()
                    .opportunityType(OpportunityType.YOUTH_POLICY)
                    .title(title)
                    .category("Category")
                    .subCategory("Subcategory")
                    .build();
            entityManager.persist(opportunity);
            entityManager.flush();
            return opportunity.getId();
        });
    }

    private CustomUserDetails principal(Long userId) {
        return new CustomUserDetails(userId, Role.USER, "test-session");
    }

    private UserOpportunityPreference findPreference(
            Long userId, Long opportunityId
    ) {
        return preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId)
                .orElseThrow();
    }
}
