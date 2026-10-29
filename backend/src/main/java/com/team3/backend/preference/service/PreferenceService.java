package com.team3.backend.preference.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 사용자별 저장·숨김 상태와 저장 목록 조회를 관리한다. */
@Service
@RequiredArgsConstructor
public class PreferenceService {

    private final UserRepository userRepository;
    private final UserOpportunityPreferenceRepository preferenceRepository;
    private final EntityManager entityManager;

    /** 사용자 잠금 아래 공고를 멱등하게 저장하고 실제 전환 여부를 반환한다. */
    @Transactional
    public boolean save(Long userId, Long opportunityId) {
        User user = getLockedUser(userId);
        Opportunity opportunity = getOpportunity(opportunityId);
        Optional<UserOpportunityPreference> existing = preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId);
        if (existing.isPresent()) {
            UserOpportunityPreference preference = existing.get();
            if (!preference.isSaved()) {
                preference.markSaved(currentVersionId(opportunity));
                return true;
            }
            return false;
        }

        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(user)
                .opportunity(opportunity)
                .build();
        preference.markSaved(currentVersionId(opportunity));
        preferenceRepository.save(preference);
        return true;
    }

    /** 저장 상태를 해제하고 두 상태가 꺼지면 행을 삭제한다. */
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

    /** 사용자 잠금 아래 공고를 멱등하게 숨기고 실제 전환 여부를 반환한다. */
    @Transactional
    public boolean hide(Long userId, Long opportunityId) {
        User user = getLockedUser(userId);
        Opportunity opportunity = getOpportunity(opportunityId);
        Optional<UserOpportunityPreference> existing = preferenceRepository
                .findByUserIdAndOpportunityId(userId, opportunityId);
        if (existing.isPresent()) {
            UserOpportunityPreference preference = existing.get();
            if (!preference.isHidden()) {
                preference.markHidden();
                return true;
            }
            return false;
        }

        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(user)
                .opportunity(opportunity)
                .build();
        preference.markHidden();
        preferenceRepository.save(preference);
        return true;
    }

    /** 숨김 상태를 해제하고 두 상태가 꺼지면 행을 삭제한다. */
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

    /** 저장 시각과 ID의 내림차순으로 저장 공고 페이지를 조회한다. */
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

    /** 지정한 공고 중 사용자가 저장한 공고 ID를 일괄 조회한다. */
    @Transactional(readOnly = true)
    public Set<Long> getSavedOpportunityIds(Long userId, Collection<Long> opportunityIds) {
        if (opportunityIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(preferenceRepository.findSavedOpportunityIds(userId, opportunityIds));
    }

    /** 사용자가 숨긴 공고 ID 전체를 한 번에 조회한다 (추천 후보 순회 전체에서 재사용). */
    @Transactional(readOnly = true)
    public Set<Long> getHiddenOpportunityIds(Long userId) {
        return Set.copyOf(preferenceRepository.findHiddenOpportunityIds(userId));
    }

    /** 저장 사용자 ID를 커서 다음부터 조회하며 첫 호출의 커서는 0이다. */
    @Transactional(readOnly = true)
    public List<Long> getSavedUserIdsAfter(Long opportunityId, Long afterUserId, int size) {
        if (size < 1) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }
        return preferenceRepository.findSavedUserIdsAfter(
                opportunityId, afterUserId, PageRequest.of(0, size));
    }

    /** 지정한 사용자 중 공고를 현재 저장한 ID를 한 번에 재확인한다. */
    @Transactional(readOnly = true)
    public Set<Long> getSavedUserIds(Long opportunityId, Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(preferenceRepository.findSavedUserIds(opportunityId, userIds));
    }

    private User getLockedUser(Long userId) {
        // 동일 사용자의 동시 변경을 직렬화해 상태 덮어쓰기와 중복 행을 막는다.
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

    private Long currentVersionId(Opportunity opportunity) {
        OpportunityVersion version = opportunity.getCurrentVersion();
        return version == null ? null : version.getId();
    }

    private void deleteIfInactive(UserOpportunityPreference preference) {
        if (preference.isInactive()) {
            preferenceRepository.delete(preference);
        }
    }
}
