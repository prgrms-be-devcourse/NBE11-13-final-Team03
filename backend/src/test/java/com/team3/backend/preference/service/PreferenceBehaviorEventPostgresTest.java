package com.team3.backend.preference.service;

import org.junit.jupiter.api.Tag;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.domain.entity.UserBehaviorEvent;
import com.team3.backend.behavior.domain.entity.UserBehaviorEventType;
import com.team3.backend.behavior.domain.entity.UserBehaviorSource;
import com.team3.backend.behavior.domain.repository.UserBehaviorEventRepository;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL에서 커밋 후 행동 기록과 기존 Preference 트랜잭션의 독립성을 검증한다. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Tag("postgres")
class PreferenceBehaviorEventPostgresTest {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private PreferenceService preferenceService;
    @Autowired private UserRepository userRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private UserOpportunityPreferenceRepository preferenceRepository;
    @Autowired private UserBehaviorEventRepository eventRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void recordsOnlySaveAndHideTransitionsWithTheirSources() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();

        postChange("save", userId, opportunityId, null);
        postChange("save", userId, opportunityId, "SEARCH");
        postChange("hide", userId, opportunityId, "RECOMMENDATION");
        postChange("hide", userId, opportunityId, null);
        var both = preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId).orElseThrow();
        assertTrue(both.isSaved());
        assertTrue(both.isHidden());
        assertEquals(List.of(UserBehaviorEventType.SAVE, UserBehaviorEventType.HIDE),
                events(userId, opportunityId).stream().map(UserBehaviorEvent::getEventType).toList());
        assertEquals(List.of(UserBehaviorSource.OTHER, UserBehaviorSource.RECOMMENDATION),
                events(userId, opportunityId).stream().map(UserBehaviorEvent::getSource).toList());

        deleteChange("save", userId, opportunityId);
        assertTrue(preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId)
                .orElseThrow().isHidden());
        assertEquals(2, events(userId, opportunityId).size());
        postChange("save", userId, opportunityId, "SAVED");
        deleteChange("hide", userId, opportunityId);
        assertTrue(preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId)
                .orElseThrow().isSaved());
        assertEquals(3, events(userId, opportunityId).size());
        postChange("hide", userId, opportunityId, "SEARCH");

        assertEquals(List.of(UserBehaviorEventType.SAVE, UserBehaviorEventType.HIDE,
                UserBehaviorEventType.SAVE, UserBehaviorEventType.HIDE),
                events(userId, opportunityId).stream().map(UserBehaviorEvent::getEventType).toList());
        assertEquals(List.of(UserBehaviorSource.OTHER, UserBehaviorSource.RECOMMENDATION,
                UserBehaviorSource.SAVED, UserBehaviorSource.SEARCH),
                events(userId, opportunityId).stream().map(UserBehaviorEvent::getSource).toList());
    }

    @Test
    void rollbackDoesNotRecordBehaviorOrPreference() {
        Long userId = createUser();
        Long opportunityId = createOpportunity();

        transactionTemplate.execute(status -> {
            assertTrue(preferenceService.save(userId, opportunityId));
            status.setRollbackOnly();
            return null;
        });

        assertTrue(preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId).isEmpty());
        assertTrue(events(userId, opportunityId).isEmpty());
    }

    @Test
    void eventInsertFailureDoesNotUndoPreferenceOrChangeNoContentResponse() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();
        jdbcTemplate.execute("""
                create function reject_preference_behavior_event() returns trigger
                language plpgsql as $$ begin raise exception 'event insert rejected'; end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_preference_behavior_event
                before insert on user_behavior_events
                for each row execute function reject_preference_behavior_event()
                """);
        try {
            postChange("save", userId, opportunityId, "SEARCH");
            postChange("hide", userId, opportunityId, "SAVED");

            var preference = preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId).orElseThrow();
            assertTrue(preference.isSaved());
            assertTrue(preference.isHidden());
            assertTrue(events(userId, opportunityId).isEmpty());
        } finally {
            jdbcTemplate.execute("drop trigger reject_preference_behavior_event on user_behavior_events");
            jdbcTemplate.execute("drop function reject_preference_behavior_event()");
        }
    }

    @Test
    void concurrentTransitionsProduceOneEventPerSuccessfulStateChange() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();

        runConcurrently(() -> postChangeUnchecked("save", userId, opportunityId),
                () -> postChangeUnchecked("hide", userId, opportunityId));
        runConcurrently(() -> postChangeUnchecked("save", userId, opportunityId),
                () -> postChangeUnchecked("save", userId, opportunityId));
        runConcurrently(() -> postChangeUnchecked("hide", userId, opportunityId),
                () -> postChangeUnchecked("hide", userId, opportunityId));

        var preference = preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId).orElseThrow();
        assertTrue(preference.isSaved());
        assertTrue(preference.isHidden());
        assertEquals(1, preferenceRepository.countByUserIdAndOpportunityId(userId, opportunityId));
        assertEquals(1, events(userId, opportunityId).stream()
                .filter(event -> event.getEventType() == UserBehaviorEventType.SAVE).count());
        assertEquals(1, events(userId, opportunityId).stream()
                .filter(event -> event.getEventType() == UserBehaviorEventType.HIDE).count());
    }

    @Test
    void concurrentUsersRecordTheirOwnTransitions() throws Exception {
        Long firstUserId = createUser();
        Long secondUserId = createUser();
        Long opportunityId = createOpportunity();

        runConcurrently(() -> postChangeUnchecked("save", firstUserId, opportunityId),
                () -> postChangeUnchecked("hide", secondUserId, opportunityId));

        assertEquals(List.of(UserBehaviorEventType.SAVE), events(firstUserId, opportunityId).stream()
                .map(UserBehaviorEvent::getEventType).toList());
        assertEquals(List.of(UserBehaviorEventType.HIDE), events(secondUserId, opportunityId).stream()
                .map(UserBehaviorEvent::getEventType).toList());
    }

    @Test
    void invalidSourceAuthenticationAndClientSaveHideRemainRestricted() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();
        var principal = user(new CustomUserDetails(userId, Role.USER, "behavior-test"));

        mockMvc.perform(post("/api/opportunities/{id}/save", opportunityId)
                        .param("source", "INVALID").with(principal).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/opportunities/{id}/hide", opportunityId)
                        .param("source", "INVALID").with(principal).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/opportunities/{id}/save", opportunityId).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/opportunities/{id}/save", opportunityId).with(principal))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/opportunities/{id}/events", opportunityId)
                        .with(principal).with(csrf()).contentType("application/json")
                        .content("""
                                {"eventType":"SAVE","source":"OTHER"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/opportunities/{id}/events", opportunityId)
                        .with(principal).with(csrf()).contentType("application/json")
                        .content("""
                                {"eventType":"HIDE","source":"OTHER"}
                                """))
                .andExpect(status().isBadRequest());
        assertTrue(events(userId, opportunityId).isEmpty());
        assertFalse(preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId).isPresent());
    }

    private void postChange(String action, Long userId, Long opportunityId, String source) throws Exception {
        var request = post("/api/opportunities/{id}/{action}", opportunityId, action)
                .with(user(new CustomUserDetails(userId, Role.USER, "behavior-test"))).with(csrf());
        if (source != null) {
            request.param("source", source);
        }
        mockMvc.perform(request).andExpect(status().isNoContent());
    }

    private void postChangeUnchecked(String action, Long userId, Long opportunityId) {
        try {
            postChange(action, userId, opportunityId, null);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private void deleteChange(String action, Long userId, Long opportunityId) throws Exception {
        mockMvc.perform(delete("/api/opportunities/{id}/{action}", opportunityId, action)
                        .with(user(new CustomUserDetails(userId, Role.USER, "behavior-test"))).with(csrf()))
                .andExpect(status().isNoContent());
    }

    private List<UserBehaviorEvent> events(Long userId, Long opportunityId) {
        return eventRepository.findAll().stream()
                .filter(event -> event.getUser().getId().equals(userId)
                        && event.getOpportunity().getId().equals(opportunityId))
                .sorted(java.util.Comparator.comparing(UserBehaviorEvent::getId))
                .toList();
    }

    private Long createUser() {
        return userRepository.saveAndFlush(User.builder().role(Role.USER).build()).getId();
    }

    private Long createOpportunity() {
        return opportunityRepository.saveAndFlush(Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY).title("Preference behavior test").build()).getId();
    }

    private void runConcurrently(Runnable first, Runnable second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> firstFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                first.run();
                return null;
            });
            Future<?> secondFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                second.run();
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            firstFuture.get(20, TimeUnit.SECONDS);
            secondFuture.get(20, TimeUnit.SECONDS);
        }
    }
}
