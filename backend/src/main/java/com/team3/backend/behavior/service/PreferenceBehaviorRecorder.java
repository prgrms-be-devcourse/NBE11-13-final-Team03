package com.team3.backend.behavior.service;

import com.team3.backend.behavior.domain.entity.UserBehaviorEventType;
import com.team3.backend.behavior.domain.entity.UserBehaviorSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 커밋된 저장·숨김 전환의 행동 기록 실패를 사용자 기능에서 격리한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceBehaviorRecorder {

    private final UserBehaviorEventService behaviorEventService;

    /** 별도 트랜잭션의 기록 실패를 로깅하고 기존 상태 변경 성공은 유지한다. */
    public void record(Long userId, Long opportunityId,
                       UserBehaviorEventType eventType, UserBehaviorSource source) {
        try {
            behaviorEventService.recordPreferenceChange(userId, opportunityId, eventType, source);
        } catch (RuntimeException exception) {
            log.warn("Preference behavior event recording failed: eventType={}, opportunityId={}",
                    eventType, opportunityId, exception);
        }
    }
}
