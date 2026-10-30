package com.team3.backend.preference.service;

import org.junit.jupiter.api.Tag;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Tag("postgres")
class PreferenceSavedUsersPostgresTest {

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
    private PreferenceService preferenceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void savedUsersAreTraversedByIdWithoutGapsOrCrossOpportunityRows() {
        Long opportunityId = createOpportunity("Saved users cursor");
        Long otherOpportunityId = createOpportunity("Other saved users cursor");
        Long first = createUser(7701L);
        Long second = createUser(7702L);
        Long unsaved = createUser(7703L);
        Long otherOnly = createUser(7704L);
        Long third = createUser(7705L);
        Long fourth = createUser(7706L);
        Long hiddenOnly = createUser(7707L);

        preferenceService.save(first, opportunityId);
        preferenceService.save(second, opportunityId);
        preferenceService.hide(second, opportunityId);
        preferenceService.save(unsaved, opportunityId);
        preferenceService.unsave(unsaved, opportunityId);
        preferenceService.save(otherOnly, otherOpportunityId);
        preferenceService.save(third, opportunityId);
        preferenceService.save(fourth, opportunityId);
        preferenceService.hide(hiddenOnly, opportunityId);

        List<Long> traversed = new ArrayList<>();
        Long afterUserId = 0L;
        List<Long> batch;
        do {
            batch = preferenceService.getSavedUserIdsAfter(opportunityId, afterUserId, 2);
            traversed.addAll(batch);
            if (!batch.isEmpty()) {
                afterUserId = batch.getLast();
            }
        } while (!batch.isEmpty());

        assertEquals(List.of(first, second, third, fourth), traversed);
        assertEquals(traversed.size(), new HashSet<>(traversed).size());
        assertEquals(List.of(second), preferenceService.getSavedUserIdsAfter(opportunityId, first, 1));
        assertEquals(List.of(), preferenceService.getSavedUserIdsAfter(opportunityId, fourth, 2));
        assertEquals(List.of(otherOnly), preferenceService.getSavedUserIdsAfter(otherOpportunityId, 0L, 2));
    }

    @Test
    void savedUsersAreRecheckedInOneQueryAndEmptyInputSkipsDatabase() {
        Long opportunityId = createOpportunity("Saved users recheck");
        Long otherOpportunityId = createOpportunity("Other saved users recheck");
        Long saved = createUser(7801L);
        Long savedAndHidden = createUser(7802L);
        Long unsaved = createUser(7803L);
        Long otherOnly = createUser(7804L);
        preferenceService.save(saved, opportunityId);
        preferenceService.save(savedAndHidden, opportunityId);
        preferenceService.hide(savedAndHidden, opportunityId);
        preferenceService.save(unsaved, opportunityId);
        preferenceService.unsave(unsaved, opportunityId);
        preferenceService.save(otherOnly, otherOpportunityId);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long beforeEmpty = statistics.getPrepareStatementCount();
            assertEquals(Set.of(), preferenceService.getSavedUserIds(opportunityId, List.of()));
            assertEquals(0, statistics.getPrepareStatementCount() - beforeEmpty);

            long beforeBatch = statistics.getPrepareStatementCount();
            assertEquals(Set.of(saved, savedAndHidden), preferenceService.getSavedUserIds(
                    opportunityId, List.of(saved, savedAndHidden, unsaved, otherOnly, Long.MAX_VALUE)));
            assertEquals(1, statistics.getPrepareStatementCount() - beforeBatch);
        } finally {
            statistics.setStatisticsEnabled(statisticsEnabled);
        }
        assertEquals(Set.of(otherOnly), preferenceService.getSavedUserIds(
                otherOpportunityId, List.of(saved, otherOnly)));
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        for (int size : List.of(0, -1)) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> preferenceService.getSavedUserIdsAfter(1L, 0L, size));
            assertEquals(GlobalErrorCode.INVALID_INPUT_VALUE, exception.getErrorCode());
        }
    }

    @Test
    void postgresCreatesSavedUsersIndexAndKeepsUniqueConstraint() {
        String indexDefinition = (String) entityManager.createNativeQuery("""
                select indexdef from pg_indexes
                where schemaname = current_schema()
                  and tablename = 'user_opportunity_preferences'
                  and indexname = 'idx_user_opportunity_preferences_saved_users'
                """).getSingleResult();
        assertTrue(indexDefinition.contains("(opportunity_id, is_saved, user_id)"));
        Number uniqueConstraints = (Number) entityManager.createNativeQuery("""
                select count(*) from pg_constraint
                where conrelid = 'user_opportunity_preferences'::regclass
                  and conname = 'uk_user_opportunity_preferences_user_opportunity'
                  and contype = 'u'
                """).getSingleResult();
        assertEquals(1L, uniqueConstraints.longValue());
    }

    private Long createUser(Long kakaoId) {
        return userRepository.saveAndFlush(User.builder()
                .kakaoId(kakaoId).role(Role.USER).build()).getId();
    }

    private Long createOpportunity(String title) {
        return transactionTemplate.execute(status -> {
            Opportunity opportunity = Opportunity.builder()
                    .opportunityType(OpportunityType.YOUTH_POLICY).title(title).build();
            entityManager.persist(opportunity);
            entityManager.flush();
            return opportunity.getId();
        });
    }
}
