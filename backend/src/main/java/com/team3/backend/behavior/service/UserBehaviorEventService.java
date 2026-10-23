package com.team3.backend.behavior.service;

import com.team3.backend.behavior.domain.entity.ExternalLinkType;
import com.team3.backend.behavior.domain.entity.UserBehaviorEvent;
import com.team3.backend.behavior.domain.entity.UserBehaviorEventType;
import com.team3.backend.behavior.domain.entity.UserBehaviorSource;
import com.team3.backend.behavior.domain.repository.UserBehaviorEventRepository;
import com.team3.backend.behavior.dto.UserBehaviorEventRequestDto;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.exception.OpportunityErrorCode;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.exception.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FE에서 발생한 인증 사용자 행동만 검증하고 저장한다. */
@Service
@RequiredArgsConstructor
public class UserBehaviorEventService {

    private final UserRepository userRepository;
    private final OpportunityRepository opportunityRepository;
    private final UserBehaviorEventRepository eventRepository;

    /** 직접 등록 가능한 행동과 링크 유형을 검증한 뒤 한 건을 저장한다. */
    @Transactional
    public void record(Long userId, Long opportunityId, UserBehaviorEventRequestDto request) {
        validate(request);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new BusinessException(OpportunityErrorCode.OPPORTUNITY_NOT_FOUND));
        if (opportunity.getCurrentVersion() == null) {
            throw new BusinessException(OpportunityErrorCode.OPPORTUNITY_NOT_FOUND);
        }

        eventRepository.save(new UserBehaviorEvent(
                user, opportunity, request.eventType(), request.source(), request.linkType()));
    }

    private void validate(UserBehaviorEventRequestDto request) {
        if (request == null || request.eventType() == null || request.source() == null) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }
        UserBehaviorEventType eventType = request.eventType();
        UserBehaviorSource source = request.source();
        ExternalLinkType linkType = request.linkType();
        if (eventType == UserBehaviorEventType.SAVE || eventType == UserBehaviorEventType.HIDE
                || eventType == UserBehaviorEventType.IMPRESSION && source != UserBehaviorSource.RECOMMENDATION
                || (eventType == UserBehaviorEventType.EXTERNAL_LINK_CLICK) != (linkType != null)) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
