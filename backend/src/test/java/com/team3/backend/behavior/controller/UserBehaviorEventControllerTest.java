package com.team3.backend.behavior.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.domain.repository.UserBehaviorEventRepository;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 행동 이벤트 API의 인증, CSRF, 입력 계약을 검증한다. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class UserBehaviorEventControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private OpportunityVersionRepository versionRepository;
    @Autowired private UserBehaviorEventRepository eventRepository;

    @Test
    void acceptsSupportedEventsAndReturnsNoContent() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();
        long before = eventRepository.count();

        record(userId, opportunityId, """
                {"eventType":"IMPRESSION","source":"RECOMMENDATION"}
                """).andExpect(status().isNoContent());
        record(userId, opportunityId, """
                {"eventType":"DETAIL_VIEW","source":"SEARCH"}
                """).andExpect(status().isNoContent());
        record(userId, opportunityId, """
                {"eventType":"EXTERNAL_LINK_CLICK","source":"SAVED","linkType":"REFERENCE"}
                """).andExpect(status().isNoContent());

        assertEquals(before + 3, eventRepository.count());
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();
        String body = """
                {"eventType":"DETAIL_VIEW","source":"OTHER"}
                """;
        mockMvc.perform(post("/api/opportunities/{id}/events", opportunityId)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/opportunities/{id}/events", opportunityId)
                        .with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnsupportedEventCombinations() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();
        long before = eventRepository.count();
        String[] invalidBodies = {
                """
                {"eventType":"SAVE","source":"OTHER"}
                """,
                """
                {"eventType":"HIDE","source":"OTHER"}
                """,
                """
                {"eventType":"IMPRESSION","source":"SEARCH"}
                """,
                """
                {"eventType":"EXTERNAL_LINK_CLICK","source":"OTHER"}
                """,
                """
                {"eventType":"DETAIL_VIEW","source":"OTHER","linkType":"DETAIL"}
                """,
                """
                {"eventType":"DETAIL_VIEW"}
                """,
                """
                {"source":"OTHER"}
                """,
                """
                {"eventType":"NOT_REAL","source":"OTHER"}
                """,
                """
                {"eventType":"DETAIL_VIEW","source":"NOT_REAL"}
                """,
                """
                {"eventType":"EXTERNAL_LINK_CLICK","source":"OTHER","linkType":"NOT_REAL"}
                """
        };
        for (String body : invalidBodies) {
            record(userId, opportunityId, body).andExpect(status().isBadRequest());
        }
        assertEquals(before, eventRepository.count());
    }

    @Test
    void rejectsUnknownOpportunity() throws Exception {
        Long userId = createUser();
        record(userId, Long.MAX_VALUE, """
                {"eventType":"DETAIL_VIEW","source":"OTHER"}
                """).andExpect(status().isNotFound());
    }

    @Test
    void payloadUserIdDoesNotReplaceAuthenticatedUser() throws Exception {
        Long userId = createUser();
        Long otherUserId = createUser();
        Long opportunityId = createOpportunity();
        long before = eventRepository.count();

        record(userId, opportunityId, """
                {"eventType":"DETAIL_VIEW","source":"OTHER","userId":%d}
                """.formatted(otherUserId)).andExpect(status().isNoContent());

        assertEquals(before + 1, eventRepository.count());
        assertEquals(userId, eventRepository.findAll().getLast().getUser().getId());
    }

    private org.springframework.test.web.servlet.ResultActions record(Long userId, Long opportunityId,
                                                                       String body) throws Exception {
        return mockMvc.perform(post("/api/opportunities/{id}/events", opportunityId)
                .with(user(principal(userId))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private CustomUserDetails principal(Long userId) {
        return new CustomUserDetails(userId, Role.USER, "behavior-test");
    }

    private Long createUser() {
        return userRepository.saveAndFlush(User.builder().role(Role.USER).build()).getId();
    }

    private Long createOpportunity() {
        Opportunity opportunity = opportunityRepository.saveAndFlush(Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY).title("Behavior test").build());
        OpportunityVersion version = versionRepository.saveAndFlush(OpportunityVersion.builder()
                .opportunity(opportunity).versionNo(1)
                .normalizedSnapshot(Map.of("title", opportunity.getTitle()))
                .personalizationStatus(PersonalizationStatus.UNKNOWN).normalizationVersion("test").build());
        opportunity.changeCurrentVersion(version);
        return opportunityRepository.saveAndFlush(opportunity).getId();
    }
}
