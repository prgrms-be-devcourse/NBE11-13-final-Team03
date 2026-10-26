package com.team3.backend.preference.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.preference.dto.SavedOpportunitiesResponseDto;
import com.team3.backend.preference.exception.PreferenceErrorCode;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.exception.UserErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
class PreferenceServiceTest {

    @Autowired
    private PreferenceService preferenceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOpportunityPreferenceRepository preferenceRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void keepsStatesIndependentAndDeletesOnlyWhenBothAreFalse() {
        Long userId = createUser(201L);
        Long opportunityId = createOpportunity("State changes");

        preferenceService.unsave(userId, opportunityId);
        preferenceService.unhide(userId, opportunityId);
        assertTrue(preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId).isEmpty());

        preferenceService.save(userId, opportunityId);
        UserOpportunityPreference saved = findPreference(userId, opportunityId);
        LocalDateTime savedAt = saved.getSavedAt();
        LocalDateTime createdAt = saved.getCreatedAt();
        LocalDateTime updatedAt = saved.getUpdatedAt();

        preferenceService.save(userId, opportunityId);
        UserOpportunityPreference repeatedSave = findPreference(userId, opportunityId);
        assertEquals(savedAt, repeatedSave.getSavedAt());
        assertEquals(updatedAt, repeatedSave.getUpdatedAt());

        preferenceService.hide(userId, opportunityId);
        UserOpportunityPreference both = findPreference(userId, opportunityId);
        assertTrue(both.isSaved());
        assertTrue(both.isHidden());
        assertEquals(savedAt, both.getSavedAt());
        LocalDateTime hiddenAt = both.getHiddenAt();

        preferenceService.hide(userId, opportunityId);
        UserOpportunityPreference repeatedHide = findPreference(userId, opportunityId);
        assertEquals(hiddenAt, repeatedHide.getHiddenAt());
        assertEquals(both.getUpdatedAt(), repeatedHide.getUpdatedAt());

        preferenceService.unsave(userId, opportunityId);
        UserOpportunityPreference hidden = findPreference(userId, opportunityId);
        assertFalse(hidden.isSaved());
        assertNull(hidden.getSavedAt());
        assertTrue(hidden.isHidden());
        assertEquals(hiddenAt, hidden.getHiddenAt());
        assertEquals(createdAt, hidden.getCreatedAt());

        preferenceService.unsave(userId, opportunityId);
        assertEquals(hidden.getUpdatedAt(),
                findPreference(userId, opportunityId).getUpdatedAt());

        preferenceService.unhide(userId, opportunityId);
        assertTrue(preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId).isEmpty());
        preferenceService.unhide(userId, opportunityId);
    }

    @Test
    void unsaveDeletesSavedOnlyPreference() {
        Long userId = createUser(202L);
        Long opportunityId = createOpportunity("Saved only");

        preferenceService.save(userId, opportunityId);
        preferenceService.unsave(userId, opportunityId);

        assertTrue(preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId).isEmpty());
    }

    @Test
    void savedVersionFollowsActualSaveTransitionsAndRemainsUserSpecific() {
        Long firstUserId = createUser(213L);
        Long secondUserId = createUser(214L);
        Long opportunityId = createOpportunity("Versioned save");
        Long firstVersionId = createVersion(opportunityId, 1);

        assertTrue(preferenceService.save(firstUserId, opportunityId));
        UserOpportunityPreference firstSaved = findPreference(firstUserId, opportunityId);
        LocalDateTime firstSavedAt = firstSaved.getSavedAt();
        assertEquals(firstVersionId, firstSaved.getSavedVersionId());

        preferenceService.hide(firstUserId, opportunityId);
        Long secondVersionId = createVersion(opportunityId, 2);
        assertFalse(preferenceService.save(firstUserId, opportunityId));
        assertEquals(firstVersionId, findPreference(firstUserId, opportunityId).getSavedVersionId());
        assertEquals(firstSavedAt, findPreference(firstUserId, opportunityId).getSavedAt());

        assertTrue(preferenceService.save(secondUserId, opportunityId));
        assertEquals(secondVersionId, findPreference(secondUserId, opportunityId).getSavedVersionId());

        preferenceService.unsave(firstUserId, opportunityId);
        UserOpportunityPreference hidden = findPreference(firstUserId, opportunityId);
        assertFalse(hidden.isSaved());
        assertTrue(hidden.isHidden());
        assertNull(hidden.getSavedVersionId());
        assertNull(hidden.getSavedAt());

        assertTrue(preferenceService.save(firstUserId, opportunityId));
        UserOpportunityPreference resaved = findPreference(firstUserId, opportunityId);
        assertTrue(resaved.isHidden());
        assertEquals(secondVersionId, resaved.getSavedVersionId());
        preferenceService.unhide(firstUserId, opportunityId);
        assertEquals(secondVersionId, findPreference(firstUserId, opportunityId).getSavedVersionId());
        assertEquals(secondVersionId, findPreference(secondUserId, opportunityId).getSavedVersionId());
    }

    @Test
    void missingVersionAndLegacySavedNullRemainSupported() {
        Long userId = createUser(215L);
        Long opportunityId = createOpportunity("Unversioned save");

        assertTrue(preferenceService.save(userId, opportunityId));
        assertNull(findPreference(userId, opportunityId).getSavedVersionId());
        LocalDateTime savedAt = findPreference(userId, opportunityId).getSavedAt();

        Long versionId = createVersion(opportunityId, 1);
        assertFalse(preferenceService.save(userId, opportunityId));
        assertNull(findPreference(userId, opportunityId).getSavedVersionId());
        assertEquals(savedAt, findPreference(userId, opportunityId).getSavedAt());

        preferenceService.unsave(userId, opportunityId);
        assertTrue(preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId).isEmpty());
        assertTrue(preferenceService.save(userId, opportunityId));
        assertEquals(versionId, findPreference(userId, opportunityId).getSavedVersionId());
    }

    @Test
    void missingOpportunityUsesNotFoundErrorForEveryOperation() {
        Long userId = createUser(203L);
        Long missingId = Long.MAX_VALUE;

        for (Runnable operation : List.<Runnable>of(
                () -> preferenceService.save(userId, missingId),
                () -> preferenceService.unsave(userId, missingId),
                () -> preferenceService.hide(userId, missingId),
                () -> preferenceService.unhide(userId, missingId)
        )) {
            BusinessException exception = assertThrows(
                    BusinessException.class, operation::run);
            assertEquals(PreferenceErrorCode.OPPORTUNITY_NOT_FOUND,
                    exception.getErrorCode());
        }
    }

    @Test
    void missingUserUsesExistingUserError() {
        Long opportunityId = createOpportunity("Missing user");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> preferenceService.save(Long.MAX_VALUE, opportunityId));

        assertEquals(UserErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void concurrentSaveAndHideCreateOneRowWithoutLostState() throws Exception {
        Long userId = createUser(204L);
        Long opportunityId = createOpportunity("Concurrent state");

        runConcurrently(
                () -> preferenceService.save(userId, opportunityId),
                () -> preferenceService.hide(userId, opportunityId)
        );

        UserOpportunityPreference preference = findPreference(userId, opportunityId);
        assertTrue(preference.isSaved());
        assertTrue(preference.isHidden());
        assertNotNull(preference.getSavedAt());
        assertNotNull(preference.getHiddenAt());
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
        assertEquals(1, preferenceRepository
                .countByUserIdAndOpportunityId(userId, opportunityId));
        assertEquals(savedAt, repeated.getSavedAt());
        assertEquals(hiddenAt, repeated.getHiddenAt());
        assertEquals(updatedAt, repeated.getUpdatedAt());

        runConcurrently(
                () -> preferenceService.unsave(userId, opportunityId),
                () -> preferenceService.unhide(userId, opportunityId)
        );

        assertTrue(preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId).isEmpty());
    }

    @Test
    void concurrentDuplicateSavesCreateOneRow() throws Exception {
        Long userId = createUser(208L);
        Long opportunityId = createOpportunity("Duplicate concurrent saves");

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

    @Test
    void savedListIsIsolatedPagedAndSortedBySavedAtThenPreferenceId() {
        Long userId = createUser(205L);
        Long otherUserId = createUser(206L);
        Long oldest = createOpportunity("Oldest");
        Long tiedFirst = createOpportunity("Tied first");
        Long tiedSecond = createOpportunity("Tied second");
        Long newest = createOpportunity("Newest");
        Long hiddenOnly = createOpportunity("Hidden only");
        LocalDateTime earlier = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime tied = earlier.plusDays(1);

        createSavedPreference(userId, oldest, earlier, false);
        createSavedPreference(userId, tiedFirst, tied, false);
        createSavedPreference(userId, tiedSecond, tied, true);
        createSavedPreference(userId, newest, tied.plusDays(1), false);
        createSavedPreference(otherUserId, oldest, tied.plusDays(2), false);
        preferenceService.hide(userId, hiddenOnly);

        SavedOpportunitiesResponseDto firstPage =
                preferenceService.getSavedOpportunities(userId, 0, 2);
        SavedOpportunitiesResponseDto secondPage =
                preferenceService.getSavedOpportunities(userId, 1, 2);

        assertEquals(List.of(newest, tiedSecond), firstPage.content().stream()
                .map(item -> item.opportunityId()).toList());
        assertEquals(List.of(tiedFirst, oldest), secondPage.content().stream()
                .map(item -> item.opportunityId()).toList());
        assertEquals(4, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertEquals(0, firstPage.page());
        assertEquals(2, firstPage.size());
        assertTrue(firstPage.hasNext());
        assertFalse(secondPage.hasNext());
        assertEquals(OpportunityType.YOUTH_POLICY, firstPage.content().getFirst().type());
        assertEquals("Newest", firstPage.content().getFirst().title());
        assertEquals(tied, firstPage.content().get(1).savedAt());
    }

    @Test
    void savedListIsEmptyAndRejectsInvalidPagination() {
        Long userId = createUser(207L);

        SavedOpportunitiesResponseDto empty =
                preferenceService.getSavedOpportunities(userId, 0, 20);
        assertTrue(empty.content().isEmpty());
        assertEquals(0, empty.totalElements());
        assertEquals(0, empty.totalPages());
        assertFalse(empty.hasNext());

        SavedOpportunitiesResponseDto maximumSize =
                preferenceService.getSavedOpportunities(userId, 0, 100);
        assertEquals(100, maximumSize.size());

        for (int[] pagination : List.of(
                new int[]{-1, 20},
                new int[]{0, 0},
                new int[]{0, 101}
        )) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> preferenceService.getSavedOpportunities(
                            userId, pagination[0], pagination[1]));
            assertEquals(GlobalErrorCode.INVALID_INPUT_VALUE,
                    exception.getErrorCode());
        }
    }

    @Test
    void savedOpportunityIdsAreScopedToUserAndPageWithOneQuery() {
        Long userId = createUser(209L);
        Long otherUserId = createUser(210L);
        Long savedId = createOpportunity("Saved in page");
        Long savedAndHiddenId = createOpportunity("Saved and hidden in page");
        Long hiddenOnlyId = createOpportunity("Hidden only in page");
        Long otherUserSavedId = createOpportunity("Other user's saved");
        Long outsidePageId = createOpportunity("Saved outside page");

        preferenceService.save(userId, savedId);
        preferenceService.save(userId, savedAndHiddenId);
        preferenceService.hide(userId, savedAndHiddenId);
        preferenceService.hide(userId, hiddenOnlyId);
        preferenceService.save(otherUserId, otherUserSavedId);
        preferenceService.save(userId, outsidePageId);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long beforeEmpty = statistics.getPrepareStatementCount();
            assertEquals(Set.of(), preferenceService.getSavedOpportunityIds(userId, List.of()));
            assertEquals(0, statistics.getPrepareStatementCount() - beforeEmpty);

            long beforeSingle = statistics.getPrepareStatementCount();
            assertEquals(Set.of(savedId),
                    preferenceService.getSavedOpportunityIds(userId, List.of(savedId)));
            assertEquals(1, statistics.getPrepareStatementCount() - beforeSingle);

            long beforePage = statistics.getPrepareStatementCount();
            assertEquals(Set.of(savedId, savedAndHiddenId),
                    preferenceService.getSavedOpportunityIds(userId, List.of(
                            savedId, savedAndHiddenId, hiddenOnlyId, otherUserSavedId,
                            Long.MAX_VALUE)));
            assertEquals(1, statistics.getPrepareStatementCount() - beforePage);
        } finally {
            statistics.setStatisticsEnabled(statisticsEnabled);
        }
    }

    @Test
    void hiddenOpportunityIdsAreScopedToUserAndCandidatesWithOneQuery() {
        Long userId = createUser(211L);
        Long otherUserId = createUser(212L);
        Long hiddenId = createOpportunity("Hidden candidate");
        Long bothId = createOpportunity("Saved and hidden candidate");
        Long savedOnlyId = createOpportunity("Saved candidate");
        Long otherUserHiddenId = createOpportunity("Other user's hidden candidate");
        Long outsideCandidatesId = createOpportunity("Hidden outside candidates");

        preferenceService.hide(userId, hiddenId);
        preferenceService.save(userId, bothId);
        preferenceService.hide(userId, bothId);
        preferenceService.save(userId, savedOnlyId);
        preferenceService.hide(otherUserId, otherUserHiddenId);
        preferenceService.hide(userId, outsideCandidatesId);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long beforeEmpty = statistics.getPrepareStatementCount();
            assertEquals(Set.of(), preferenceService.getHiddenOpportunityIds(userId, List.of()));
            assertEquals(0, statistics.getPrepareStatementCount() - beforeEmpty);

            long beforeCandidates = statistics.getPrepareStatementCount();
            assertEquals(Set.of(hiddenId, bothId), preferenceService.getHiddenOpportunityIds(userId,
                    List.of(hiddenId, bothId, bothId, savedOnlyId, otherUserHiddenId, Long.MAX_VALUE)));
            assertEquals(1, statistics.getPrepareStatementCount() - beforeCandidates);
        } finally {
            statistics.setStatisticsEnabled(statisticsEnabled);
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
                    .build();
            entityManager.persist(opportunity);
            entityManager.flush();
            return opportunity.getId();
        });
    }

    private Long createVersion(Long opportunityId, int versionNo) {
        return transactionTemplate.execute(status -> {
            Opportunity opportunity = entityManager.find(Opportunity.class, opportunityId);
            OpportunityVersion version = OpportunityVersion.builder()
                    .opportunity(opportunity)
                    .versionNo(versionNo)
                    .normalizationVersion("test")
                    .normalizedSnapshot(Map.of("versionNo", versionNo))
                    .personalizationStatus(PersonalizationStatus.UNKNOWN)
                    .build();
            entityManager.persist(version);
            opportunity.changeCurrentVersion(version);
            entityManager.flush();
            return version.getId();
        });
    }

    private void createSavedPreference(
            Long userId, Long opportunityId,
            LocalDateTime savedAt, boolean hidden
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            User user = entityManager.find(User.class, userId);
            Opportunity opportunity = entityManager.find(Opportunity.class, opportunityId);
            preferenceRepository.saveAndFlush(UserOpportunityPreference.builder()
                    .user(user)
                    .opportunity(opportunity)
                    .isSaved(true)
                    .isHidden(hidden)
                    .savedAt(savedAt)
                    .hiddenAt(hidden ? savedAt : null)
                    .build());
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
