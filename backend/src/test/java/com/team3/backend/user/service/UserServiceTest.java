package com.team3.backend.user.service;

import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.exception.UserErrorCode;
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

    @InjectMocks
    private UserService userService;

    @Test
    void 회원_탈퇴시_연결_데이터와_회원을_순서대로_삭제한다() {
        Long userId = 1L;

        when(userRepository.existsById(userId))
                .thenReturn(true);

        userService.withdraw(userId);

        InOrder inOrder = inOrder(
                refreshTokenRepository,
                eligibilityResultRepository,
                eligibilityProfileRepository,
                userRepository
        );

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

        when(userRepository.existsById(userId))
                .thenReturn(false);

        assertThatThrownBy(() -> userService.withdraw(userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);

        verify(userRepository, never())
                .deleteById(userId);

        verifyNoInteractions(
                refreshTokenRepository,
                eligibilityResultRepository,
                eligibilityProfileRepository
        );
    }
}