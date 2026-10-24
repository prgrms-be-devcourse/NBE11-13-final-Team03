package com.team3.backend.behavior.dto;

import com.team3.backend.behavior.domain.entity.ExternalLinkType;
import com.team3.backend.behavior.domain.entity.UserBehaviorEventType;
import com.team3.backend.behavior.domain.entity.UserBehaviorSource;
import jakarta.validation.constraints.NotNull;

/** FE 행동 이벤트의 필수 종류와 출처, 선택적 링크 유형을 받는다. */
public record UserBehaviorEventRequestDto(
        @NotNull UserBehaviorEventType eventType,
        @NotNull UserBehaviorSource source,
        ExternalLinkType linkType
) {
}
