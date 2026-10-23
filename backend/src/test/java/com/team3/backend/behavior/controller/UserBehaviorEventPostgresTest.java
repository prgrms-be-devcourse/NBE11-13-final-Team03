package com.team3.backend.behavior.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.domain.entity.ExternalLinkType;
import com.team3.backend.behavior.domain.entity.UserBehaviorEventType;
import com.team3.backend.behavior.domain.entity.UserBehaviorSource;
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
import com.team3.backend.user.service.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL의 application 스키마, 반복 저장, 회원 탈퇴 FK를 검증한다. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class UserBehaviorEventPostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("yon_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("behavior-schema.sql");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO application, pg_catalog");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "application");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private OpportunityVersionRepository versionRepository;
    @Autowired private UserBehaviorEventRepository eventRepository;
    @Autowired private UserService userService;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void storesAuthenticatedEventsAndAllowsRepeatedActions() throws Exception {
        Long userId = createUser();
        Long otherUserId = createUser();
        Long opportunityId = createOpportunity();
        LocalDateTime started = LocalDateTime.now();
        String impression = """
                {"eventType":"IMPRESSION","source":"RECOMMENDATION"}
                """;

        record(userId, opportunityId, impression);
        record(userId, opportunityId, impression);
        record(otherUserId, opportunityId, """
                {"eventType":"EXTERNAL_LINK_CLICK","source":"SEARCH","linkType":"APPLICATION"}
                """);

        var events = eventRepository.findAll().stream()
                .filter(event -> event.getOpportunity().getId().equals(opportunityId))
                .sorted(Comparator.comparing(event -> event.getId()))
                .toList();
        assertEquals(3, events.size());
        assertEquals(userId, events.get(0).getUser().getId());
        assertEquals(userId, events.get(1).getUser().getId());
        assertEquals(otherUserId, events.get(2).getUser().getId());
        assertEquals(opportunityId, events.get(0).getOpportunity().getId());
        assertEquals(UserBehaviorEventType.IMPRESSION, events.get(0).getEventType());
        assertEquals(UserBehaviorSource.RECOMMENDATION, events.get(0).getSource());
        assertNull(events.get(0).getLinkType());
        assertEquals(ExternalLinkType.APPLICATION, events.get(2).getLinkType());
        assertNotNull(events.get(0).getReceivedAt());
        assertFalse(events.get(0).getReceivedAt().isBefore(started.minusSeconds(1)));
        assertFalse(events.get(0).getReceivedAt().isAfter(LocalDateTime.now().plusSeconds(1)));
    }

    @Test
    void withdrawalDeletesOnlyOwnersEventsAndKeepsOpportunity() throws Exception {
        Long userId = createUser();
        Long otherUserId = createUser();
        Long opportunityId = createOpportunity();
        record(userId, opportunityId, """
                {"eventType":"DETAIL_VIEW","source":"OTHER"}
                """);
        record(otherUserId, opportunityId, """
                {"eventType":"DETAIL_VIEW","source":"OTHER"}
                """);

        userService.withdraw(userId);

        assertFalse(userRepository.existsById(userId));
        assertTrue(userRepository.existsById(otherUserId));
        assertTrue(opportunityRepository.existsById(opportunityId));
        assertEquals(0, eventRepository.findAll().stream()
                .filter(event -> event.getUser().getId().equals(userId)).count());
        assertEquals(1, eventRepository.findAll().stream()
                .filter(event -> event.getUser().getId().equals(otherUserId)
                        && event.getOpportunity().getId().equals(opportunityId)).count());
    }

    @Test
    void unnormalizedOpportunityIsNotRecorded() throws Exception {
        Long userId = createUser();
        Long opportunityId = opportunityRepository.saveAndFlush(Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY).title("Unnormalized behavior target")
                .build()).getId();
        long before = eventRepository.count();

        for (String body : new String[] {
                """
                {"eventType":"IMPRESSION","source":"RECOMMENDATION"}
                """,
                """
                {"eventType":"DETAIL_VIEW","source":"OTHER"}
                """,
                """
                {"eventType":"EXTERNAL_LINK_CLICK","source":"OTHER","linkType":"DETAIL"}
                """
        }) {
            mockMvc.perform(post("/api/opportunities/{id}/events", opportunityId)
                            .with(user(new CustomUserDetails(userId, Role.USER, "behavior-test")))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound());
        }
        assertEquals(before, eventRepository.count());

        Long normalizedId = createOpportunity();
        record(userId, normalizedId, """
                {"eventType":"DETAIL_VIEW","source":"OTHER"}
                """);
        assertEquals(before + 1, eventRepository.count());
    }

    @Test
    void receivedAtRoundTripsWithoutSessionTimezoneConversion() throws Exception {
        Long userId = createUser();
        Long opportunityId = createOpportunity();
        LocalDateTime before = LocalDateTime.now();
        record(userId, opportunityId, """
                {"eventType":"DETAIL_VIEW","source":"OTHER"}
                """);
        LocalDateTime after = LocalDateTime.now();

        var event = eventRepository.findAll().stream()
                .filter(candidate -> candidate.getOpportunity().getId().equals(opportunityId))
                .findFirst().orElseThrow();
        LocalDateTime rawValue = jdbcTemplate.queryForObject(
                "select received_at from application.user_behavior_events where id = ?",
                (resultSet, rowNumber) -> resultSet.getObject(1, LocalDateTime.class), event.getId());
        String columnType = jdbcTemplate.queryForObject("""
                select data_type from information_schema.columns
                where table_schema = 'application' and table_name = 'user_behavior_events'
                  and column_name = 'received_at'
                """, String.class);
        String databaseZone = jdbcTemplate.queryForObject("show TimeZone", String.class);

        assertEquals("timestamp without time zone", columnType);
        assertEquals(event.getReceivedAt(), rawValue);
        assertFalse(rawValue.isBefore(before.minusSeconds(1)));
        assertFalse(rawValue.isAfter(after.plusSeconds(1)));
        System.out.printf("Behavior event time zones: JVM=%s, PostgreSQL=%s%n",
                ZoneId.systemDefault(), databaseZone);
    }

    @Test
    void createsApplicationTableAndOnlyUserCascade() {
        String schemaName = (String) entityManager.createNativeQuery("select current_schema()").getSingleResult();
        assertEquals("application", schemaName);
        assertNotNull(entityManager.createNativeQuery(
                "select to_regclass('application.user_behavior_events')::text").getSingleResult());
        String userForeignKey = (String) entityManager.createNativeQuery("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'application.user_behavior_events'::regclass
                  and confrelid = 'application.users'::regclass and contype = 'f'
                """).getSingleResult();
        String opportunityForeignKey = (String) entityManager.createNativeQuery("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'application.user_behavior_events'::regclass
                  and confrelid = 'application.opportunities'::regclass and contype = 'f'
                """).getSingleResult();
        assertTrue(userForeignKey.contains("ON DELETE CASCADE"));
        assertFalse(opportunityForeignKey.contains("ON DELETE CASCADE"));
    }

    private void record(Long userId, Long opportunityId, String body) throws Exception {
        mockMvc.perform(post("/api/opportunities/{id}/events", opportunityId)
                        .with(user(new CustomUserDetails(userId, Role.USER, "behavior-test")))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private Long createUser() {
        return userRepository.saveAndFlush(User.builder().role(Role.USER).build()).getId();
    }

    private Long createOpportunity() {
        Opportunity opportunity = opportunityRepository.saveAndFlush(Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY).title("Behavior postgres test").build());
        OpportunityVersion version = versionRepository.saveAndFlush(OpportunityVersion.builder()
                .opportunity(opportunity).versionNo(1)
                .normalizedSnapshot(Map.of("title", opportunity.getTitle()))
                .personalizationStatus(PersonalizationStatus.UNKNOWN).normalizationVersion("test").build());
        opportunity.changeCurrentVersion(version);
        return opportunityRepository.saveAndFlush(opportunity).getId();
    }
}
