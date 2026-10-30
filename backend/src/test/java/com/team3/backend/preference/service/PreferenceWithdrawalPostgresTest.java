package com.team3.backend.preference.service;

import org.junit.jupiter.api.Tag;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.service.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Tag("postgres")
class PreferenceWithdrawalPostgresTest {

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
    private UserService userService;

    @Autowired
    private PreferenceService preferenceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOpportunityPreferenceRepository preferenceRepository;

    @Autowired
    private OpportunityRepository opportunityRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void withdrawingSavedUserDeletesOnlyTheirPreferences() {
        Long opportunityId = createOpportunity("Saved before withdrawal");
        Long withdrawingUserId = createUser(8101L);
        Long remainingUserId = createUser(8102L);
        preferenceService.save(withdrawingUserId, opportunityId);
        preferenceService.save(remainingUserId, opportunityId);

        userService.withdraw(withdrawingUserId);

        assertFalse(userRepository.existsById(withdrawingUserId));
        assertEquals(0, preferenceRepository.countByUserIdAndOpportunityId(withdrawingUserId, opportunityId));
        assertTrue(userRepository.existsById(remainingUserId));
        assertEquals(1, preferenceRepository.countByUserIdAndOpportunityId(remainingUserId, opportunityId));
        assertTrue(opportunityRepository.existsById(opportunityId));
    }

    @Test
    void withdrawingHiddenUserDeletesPreferenceButKeepsOpportunity() {
        Long opportunityId = createOpportunity("Hidden before withdrawal");
        Long userId = createUser(8201L);
        preferenceService.hide(userId, opportunityId);

        userService.withdraw(userId);

        assertFalse(userRepository.existsById(userId));
        assertEquals(0, preferenceRepository.countByUserIdAndOpportunityId(userId, opportunityId));
        assertTrue(opportunityRepository.existsById(opportunityId));
    }

    @Test
    void withdrawingSavedAndHiddenUserDeletesPreferenceButKeepsOpportunity() {
        Long opportunityId = createOpportunity("Saved and hidden before withdrawal");
        Long userId = createUser(8301L);
        preferenceService.save(userId, opportunityId);
        preferenceService.hide(userId, opportunityId);

        userService.withdraw(userId);

        assertFalse(userRepository.existsById(userId));
        assertEquals(0, preferenceRepository.countByUserIdAndOpportunityId(userId, opportunityId));
        assertTrue(opportunityRepository.existsById(opportunityId));
    }

    @Test
    void onlyUserForeignKeyCascadesInPostgres() {
        String userForeignKey = (String) entityManager.createNativeQuery("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'user_opportunity_preferences'::regclass
                  and confrelid = 'users'::regclass
                  and contype = 'f'
                """).getSingleResult();
        String opportunityForeignKey = (String) entityManager.createNativeQuery("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'user_opportunity_preferences'::regclass
                  and confrelid = 'opportunities'::regclass
                  and contype = 'f'
                """).getSingleResult();

        assertTrue(userForeignKey.contains("FOREIGN KEY (user_id)"));
        assertTrue(userForeignKey.contains("ON DELETE CASCADE"));
        assertTrue(opportunityForeignKey.contains("FOREIGN KEY (opportunity_id)"));
        assertFalse(opportunityForeignKey.contains("ON DELETE CASCADE"));
    }

    private Long createUser(Long kakaoId) {
        return userRepository.saveAndFlush(User.builder()
                .kakaoId(kakaoId).role(Role.USER).build()).getId();
    }

    private Long createOpportunity(String title) {
        return opportunityRepository.saveAndFlush(Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY).title(title).build()).getId();
    }
}
