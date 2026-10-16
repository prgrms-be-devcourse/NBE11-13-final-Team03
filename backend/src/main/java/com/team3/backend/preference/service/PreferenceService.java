package com.team3.backend.preference.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.preference.dto.SavedOpportunitiesResponseDto;
import com.team3.backend.preference.dto.SavedOpportunityResponseDto;
import com.team3.backend.preference.exception.PreferenceErrorCode;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.exception.UserErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PreferenceService {

    private final UserRepository userRepository;
    private final UserOpportunityPreferenceRepository preferenceRepository;
    private final EntityManager entityManager;

    @Transactional
    public void save(Long userId, Long opportunityId) {
        User user = getLockedUser(userId);
        Opportunity opportunity = getOpportunity(opportunityId);
        Optional<UserOpportunityPreference> existing = preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId);
        if (existing.isPresent()) {
            existing.get().markSaved();
            return;
        }

        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(user)
                .opportunity(opportunity)
                .build();
        preference.markSaved();
        preferenceRepository.save(preference);
    }

    @Transactional
    public void unsave(Long userId, Long opportunityId) {
        getLockedUser(userId);
        getOpportunity(opportunityId);
        preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId)
                .ifPresent(preference -> {
                    preference.clearSaved();
                    deleteIfInactive(preference);
                });
    }

    @Transactional
    public void hide(Long userId, Long opportunityId) {
        User user = getLockedUser(userId);
        Opportunity opportunity = getOpportunity(opportunityId);
        Optional<UserOpportunityPreference> existing = preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId);
        if (existing.isPresent()) {
            existing.get().markHidden();
            return;
        }

        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(user)
                .opportunity(opportunity)
                .build();
        preference.markHidden();
        preferenceRepository.save(preference);
    }

    @Transactional
    public void unhide(Long userId, Long opportunityId) {
        getLockedUser(userId);
        getOpportunity(opportunityId);
        preferenceRepository.findByUserIdAndOpportunityId(userId, opportunityId)
                .ifPresent(preference -> {
                    preference.clearHidden();
                    deleteIfInactive(preference);
                });
    }

    @Transactional(readOnly = true)
    public SavedOpportunitiesResponseDto getSavedOpportunities(
            Long userId, int page, int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }

        if (!userRepository.existsById(userId)) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }

        Sort sort = Sort.by(Sort.Direction.DESC, "savedAt")
                .and(Sort.by(Sort.Direction.DESC, "id"));
        Page<SavedOpportunityResponseDto> results = preferenceRepository
                .findSavedPageByUserId(userId, PageRequest.of(page, size, sort))
                .map(SavedOpportunityResponseDto::from);

        return new SavedOpportunitiesResponseDto(
                results.getContent(),
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages(),
                results.hasNext()
        );
    }

    private User getLockedUser(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    private Opportunity getOpportunity(Long opportunityId) {
        Opportunity opportunity = entityManager.find(Opportunity.class, opportunityId);
        if (opportunity == null) {
            throw new BusinessException(PreferenceErrorCode.OPPORTUNITY_NOT_FOUND);
        }
        return opportunity;
    }

    private void deleteIfInactive(UserOpportunityPreference preference) {
        if (preference.isInactive()) {
            preferenceRepository.delete(preference);
        }
    }
}
