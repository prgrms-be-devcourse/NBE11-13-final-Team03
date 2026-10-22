package com.team3.backend.preference.service;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PreferencePostgresConcurrencyTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("yon_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name",
                postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private PreferenceService preferenceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOpportunityPreferenceRepository preferenceRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void concurrentSaveAndHidePreserveBothStatesAndDeleteWhenCleared()
            throws Exception {
        Long userId = createUser(401L);
        Long opportunityId = createOpportunity("Concurrent save and hide");

        runConcurrently(
                () -> preferenceService.save(userId, opportunityId),
                () -> preferenceService.hide(userId, opportunityId)
        );

        UserOpportunityPreference preference = findPreference(userId, opportunityId);
        assertTrue(preference.isSaved());
        assertTrue(preference.isHidden());
        assertEquals(1, preferenceRepository
                .countByUserIdAndOpportunityId(userId, opportunityId));
        LocalDateTime savedAt = preference.getSavedAt();
        LocalDateTime hiddenAt = preference.getHiddenAt();
        LocalDateTime updatedAt = preference.getUpdatedAt();

        runConcurrently(
                () -> preferenceService.save(userId, opportunityId),
                () -> preferenceService.hide(userId, opportunityId)
        );

        UserOpportunityPreference repeated = findPreference(userId, opportunityId);
        assertEquals(savedAt, repeated.getSavedAt());
        assertEquals(hiddenAt, repeated.getHiddenAt());
        assertEquals(updatedAt, repeated.getUpdatedAt());
        assertEquals(1, preferenceRepository
                .countByUserIdAndOpportunityId(userId, opportunityId));

        runConcurrently(
                () -> preferenceService.unsave(userId, opportunityId),
                () -> preferenceService.unhide(userId, opportunityId)
        );

        assertTrue(preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId).isEmpty());
    }

    @Test
    void concurrentDuplicateSavesCreateOneRow() throws Exception {
        Long userId = createUser(402L);
        Long opportunityId = createOpportunity("Duplicate saves");

        runConcurrently(
                () -> preferenceService.save(userId, opportunityId),
                () -> preferenceService.save(userId, opportunityId)
        );

        UserOpportunityPreference preference = findPreference(userId, opportunityId);
        assertTrue(preference.isSaved());
        assertFalse(preference.isHidden());
        assertNotNull(preference.getSavedAt());
        assertEquals(1, preferenceRepository
                .countByUserIdAndOpportunityId(userId, opportunityId));
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
                    .build();
            entityManager.persist(opportunity);
            entityManager.flush();
            return opportunity.getId();
        });
    }

    private UserOpportunityPreference findPreference(
            Long userId, Long opportunityId
    ) {
        return preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId)
                .orElseThrow();
    }

    private void runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
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
        } finally {
            executor.shutdownNow();
        }
    }
}
