package com.team3.backend.preference.service;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.domain.entity.UserBehaviorEventType;
import com.team3.backend.behavior.domain.entity.UserBehaviorSource;
import com.team3.backend.behavior.domain.repository.UserBehaviorEventRepository;
import com.team3.backend.behavior.service.PreferenceBehaviorRecorder;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 단일 PostgreSQL 커넥션 풀에서 상태 변경과 행동 기록의 자원 경계를 검증한다. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class PreferenceBehaviorSmallPoolPostgresTest {

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 1000);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PreferenceService preferenceService;
    @Autowired private PreferenceBehaviorRecorder behaviorRecorder;
    @Autowired private UserRepository userRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private UserOpportunityPreferenceRepository preferenceRepository;
    @Autowired private UserBehaviorEventRepository eventRepository;
    @Autowired private HikariDataSource dataSource;

    @Test
    void singleConnectionIsReturnedBeforeBehaviorRecording() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();

        postSave(userId, opportunityId);

        assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
        assertEquals(1, eventRepository.findAll().stream()
                .filter(event -> event.getUser().getId().equals(userId)
                        && event.getOpportunity().getId().equals(opportunityId)).count());
    }

    @Test
    void concurrentUsersFinishWithOneConnection() throws Exception {
        Long firstUserId = createUser();
        Long secondUserId = createUser();
        Long opportunityId = createOpportunity();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                postSave(firstUserId, opportunityId);
                return null;
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                postSave(secondUserId, opportunityId);
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }

        assertEquals(2, eventRepository.findAll().stream()
                .filter(event -> event.getOpportunity().getId().equals(opportunityId)).count());
        assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void connectionAcquisitionFailureDoesNotUndoCommittedPreference() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();
        assertTrue(preferenceService.save(userId, opportunityId));

        try (Connection heldConnection = dataSource.getConnection()) {
            // 풀의 유일한 커넥션을 점유하여 이벤트 기록만 실제 획득 시간 초과로 실패시킨다.
            assertEquals(1, dataSource.getHikariPoolMXBean().getActiveConnections());
            behaviorRecorder.record(userId, opportunityId, UserBehaviorEventType.SAVE, UserBehaviorSource.OTHER);
        }

        assertTrue(preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId)
                .orElseThrow().isSaved());
        assertEquals(0, eventRepository.findAll().stream()
                .filter(event -> event.getOpportunity().getId().equals(opportunityId)).count());
    }

    private void postSave(Long userId, Long opportunityId) throws Exception {
        mockMvc.perform(post("/api/opportunities/{id}/save", opportunityId)
                        .with(user(new CustomUserDetails(userId, Role.USER, "behavior-test"))).with(csrf()))
                .andExpect(status().isNoContent());
    }

    private Long createUser() {
        return userRepository.saveAndFlush(User.builder().role(Role.USER).build()).getId();
    }

    private Long createOpportunity() {
        return opportunityRepository.saveAndFlush(Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY).title("Small pool behavior test").build()).getId();
    }
}
