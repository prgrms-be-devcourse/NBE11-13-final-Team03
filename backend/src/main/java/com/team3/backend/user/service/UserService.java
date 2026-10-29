package com.team3.backend.user.service;

import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.dto.UserMeResponseDto;
import com.team3.backend.user.exception.UserErrorCode;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import java.time.Clock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EligibilityResultRepository eligibilityResultRepository;
    private final EligibilityProfileRepository eligibilityProfileRepository;
    private final OutboxEventRepository outboxRepository;
    private final Clock clock;


    @Operation(
            summary = "내 정보 조회",
            description = """
                    현재 로그인한 사용자의 정보를 조회합니다.
                    
                    Access Token을 통해 인증된 사용자의 정보를 반환합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "내 정보 조회 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Access Token이 없거나 유효하지 않은 경우"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자 정보를 찾을 수 없는 경우"
            )
    })
    @SecurityRequirement(name = "cookieAuth")
    @Transactional(readOnly = true)
    public UserMeResponseDto getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        return UserMeResponseDto.from(user);
    }

    @Transactional
    public void withdraw(Long userId) {
        // Serialize with preference writes and fan-out; cancellation must see every committed user Outbox.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        outboxRepository.cancelPendingNotifications(userId);
        outboxRepository.saveAndFlush(OutboxEvent.userWithdrawn(userId, clock.instant()));

        // User를 직접 참조하는 데이터부터 삭제한다.
        // user_interests, user_opportunity_preferences, user_behavior_events,
        // eligibility_event_assessments와 notification_match_pairs는 사용자 삭제 시 DB ON DELETE CASCADE로 삭제된다.
        // notification_match_candidates는 쌍(notification_match_pairs) 삭제에 이어 CASCADE로 삭제된다.
        refreshTokenRepository.deleteAllByUserId(userId);

        // 사용자별 정책 판정 데이터 삭제
        eligibilityResultRepository.deleteAllByUserId(userId);

        // 맞춤 조건 삭제
        eligibilityProfileRepository.deleteAllByUserId(userId);

        // 마지막으로 사용자 삭제
        userRepository.deleteById(userId);
    }
}
