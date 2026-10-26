package com.team3.backend.preference.domain.repository;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class UserOpportunityPreferenceRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOpportunityPreferenceRepository preferenceRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findsPreferenceByUserAndOpportunity() {
        User user = createUser(100L);
        Opportunity opportunity = createOpportunity("Policy");
        UserOpportunityPreference preference = preferenceRepository.saveAndFlush(
                UserOpportunityPreference.builder()
                        .user(user)
                        .opportunity(opportunity)
                        .build()
        );
        entityManager.clear();

        UserOpportunityPreference found = preferenceRepository
                .findByUserIdAndOpportunityId(user.getId(), opportunity.getId())
                .orElseThrow();

        assertEquals(preference.getId(), found.getId());
        assertFalse(found.isSaved());
        assertFalse(found.isHidden());
        assertTrue(found.isInactive());
        assertNull(found.getSavedAt());
        assertNull(found.getHiddenAt());
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
        assertFalse(entityManager.getEntityManagerFactory()
                .getPersistenceUnitUtil().isLoaded(found, "user"));
        assertFalse(entityManager.getEntityManagerFactory()
                .getPersistenceUnitUtil().isLoaded(found, "opportunity"));
        assertTrue(preferenceRepository
                .findByUserIdAndOpportunityId(user.getId(), opportunity.getId() + 1)
                .isEmpty());
    }

    @Test
    void findsOnlySavedPreferencesForUserIncludingHiddenOnes() {
        User user = createUser(101L);
        User otherUser = createUser(102L);
        Opportunity saved = createOpportunity("Saved");
        Opportunity savedAndHidden = createOpportunity("Saved and hidden");
        Opportunity hidden = createOpportunity("Hidden");

        UserOpportunityPreference savedPreference =
                UserOpportunityPreference.builder()
                        .user(user).opportunity(saved).build();
        savedPreference.markSaved(null);
        preferenceRepository.save(savedPreference);

        UserOpportunityPreference bothPreference =
                UserOpportunityPreference.builder()
                        .user(user).opportunity(savedAndHidden)
                        .build();
        bothPreference.markSaved(null);
        bothPreference.markHidden();
        preferenceRepository.save(bothPreference);

        UserOpportunityPreference hiddenPreference = UserOpportunityPreference.builder()
                .user(user).opportunity(hidden).build();
        hiddenPreference.markHidden();
        preferenceRepository.save(hiddenPreference);

        UserOpportunityPreference otherPreference = UserOpportunityPreference.builder()
                .user(otherUser).opportunity(saved).build();
        otherPreference.markSaved(null);
        preferenceRepository.save(otherPreference);
        preferenceRepository.flush();
        entityManager.clear();

        List<UserOpportunityPreference> results =
                preferenceRepository.findSavedByUserId(user.getId());

        assertEquals(2, results.size());
        assertEquals(Set.of(savedPreference.getId(), bothPreference.getId()),
                results.stream().map(UserOpportunityPreference::getId)
                        .collect(Collectors.toSet()));

        Sort sort = Sort.by(Sort.Direction.DESC, "savedAt", "id");
        Page<UserOpportunityPreference> firstPage = preferenceRepository
                .findSavedPageByUserId(user.getId(), PageRequest.of(0, 1, sort));
        Page<UserOpportunityPreference> secondPage = preferenceRepository
                .findSavedPageByUserId(user.getId(), PageRequest.of(1, 1, sort));
        assertEquals(2, firstPage.getTotalElements());
        assertEquals(1, firstPage.getNumberOfElements());
        assertEquals(1, secondPage.getNumberOfElements());
        assertEquals(Set.of(savedPreference.getId(), bothPreference.getId()),
                Set.of(firstPage.getContent().getFirst().getId(),
                        secondPage.getContent().getFirst().getId()));
    }

    @Test
    void rejectsDuplicateUserAndOpportunity() {
        User user = createUser(103L);
        Opportunity opportunity = createOpportunity("Duplicate");
        preferenceRepository.saveAndFlush(UserOpportunityPreference.builder()
                .user(user).opportunity(opportunity).build());

        assertThrows(DataIntegrityViolationException.class, () ->
                preferenceRepository.saveAndFlush(UserOpportunityPreference.builder()
                        .user(user).opportunity(opportunity).build())
        );
    }

    @Test
    void persistsStateChangesAndLeavesTimestampsOnRepeatedRequests() {
        User user = createUser(104L);
        Opportunity opportunity = createOpportunity("State changes");
        UserOpportunityPreference preference = preferenceRepository.saveAndFlush(
                UserOpportunityPreference.builder()
                        .user(user).opportunity(opportunity).build()
        );
        entityManager.clear();
        preference = preferenceRepository.findById(preference.getId()).orElseThrow();
        LocalDateTime createdAt = preference.getCreatedAt();

        preference.markSaved(null);
        preference.markHidden();
        preferenceRepository.flush();
        entityManager.clear();

        UserOpportunityPreference found = preferenceRepository
                .findByUserIdAndOpportunityId(user.getId(), opportunity.getId())
                .orElseThrow();
        assertTrue(found.isSaved());
        assertTrue(found.isHidden());
        assertNotNull(found.getSavedAt());
        assertNotNull(found.getHiddenAt());
        assertEquals(createdAt, found.getCreatedAt());

        LocalDateTime savedAt = found.getSavedAt();
        LocalDateTime hiddenAt = found.getHiddenAt();
        LocalDateTime updatedAt = found.getUpdatedAt();
        found.markSaved(null);
        found.markHidden();
        preferenceRepository.flush();
        assertEquals(savedAt, found.getSavedAt());
        assertEquals(hiddenAt, found.getHiddenAt());
        assertEquals(updatedAt, found.getUpdatedAt());

        found.clearSaved();
        preferenceRepository.flush();
        entityManager.clear();

        UserOpportunityPreference remaining = preferenceRepository
                .findByUserIdAndOpportunityId(user.getId(), opportunity.getId())
                .orElseThrow();
        assertFalse(remaining.isSaved());
        assertNull(remaining.getSavedAt());
        assertTrue(remaining.isHidden());
        assertEquals(hiddenAt, remaining.getHiddenAt());
        assertEquals(createdAt, remaining.getCreatedAt());
    }

    private User createUser(Long kakaoId) {
        return userRepository.saveAndFlush(User.builder()
                .kakaoId(kakaoId)
                .role(Role.USER)
                .build());
    }

    private Opportunity createOpportunity(String title) {
        Opportunity opportunity = Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY)
                .title(title)
                .build();
        entityManager.persist(opportunity);
        entityManager.flush();
        return opportunity;
    }
}
