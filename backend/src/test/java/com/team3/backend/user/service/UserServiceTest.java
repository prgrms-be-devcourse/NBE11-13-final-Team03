package com.team3.backend.user.service;

import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.exception.UserErrorCode;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private EligibilityResultRepository eligibilityResultRepository;

    @Mock
    private EligibilityProfileRepository eligibilityProfileRepository;
    @Mock private OutboxEventRepository outboxRepository;
    @Mock private Clock clock;
    @Mock private com.team3.backend.auth.unlink.KakaoIdentityGuard kakaoIdentityGuard;
    @Mock private com.team3.backend.auth.unlink.KakaoUnlinkJobs kakaoUnlinkJobs;

    @InjectMocks
    private UserService userService;

    @Test
    void 회원_탈퇴시_연결_데이터와_회원을_순서대로_삭제한다() {
        Long userId = 1L;

        var user = org.mockito.Mockito.mock(User.class);
        when(user.getKakaoId()).thenReturn(123L);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T00:00:00Z"));

        userService.withdraw(userId);

        InOrder inOrder = inOrder(
                kakaoIdentityGuard,
                kakaoUnlinkJobs,
                outboxRepository,
                refreshTokenRepository,
                eligibilityResultRepository,
                eligibilityProfileRepository,
                userRepository
        );

        inOrder.verify(kakaoIdentityGuard).lock(123L);
        inOrder.verify(kakaoUnlinkJobs).enqueue(123L);
        inOrder.verify(outboxRepository).cancelPendingNotifications(userId);
        inOrder.verify(outboxRepository).saveAndFlush(any(OutboxEvent.class));
        inOrder.verify(refreshTokenRepository)
                .deleteAllByUserId(userId);
        inOrder.verify(eligibilityResultRepository)
                .deleteAllByUserId(userId);
        inOrder.verify(eligibilityProfileRepository)
                .deleteAllByUserId(userId);
        inOrder.verify(userRepository)
                .deleteById(userId);
    }

    @Test
    void 존재하지_않는_회원은_탈퇴할_수_없다() {
        Long userId = 99L;

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.withdraw(userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);

        verify(userRepository, never())
                .deleteById(userId);

        verifyNoInteractions(
                outboxRepository,
                refreshTokenRepository,
                eligibilityResultRepository,
                eligibilityProfileRepository
        );
    }
}
