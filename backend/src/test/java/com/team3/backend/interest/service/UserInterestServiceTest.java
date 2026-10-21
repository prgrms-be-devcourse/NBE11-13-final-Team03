package com.team3.backend.interest.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.domain.entity.UserInterest;
import com.team3.backend.interest.domain.repository.UserInterestRepository;
import com.team3.backend.interest.dto.UserInterestItemRequest;
import com.team3.backend.interest.dto.UserInterestsResponse;
import com.team3.backend.interest.dto.UserInterestsUpdateRequest;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.exception.UserErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserInterestServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserInterestRepository userInterestRepository;

    @InjectMocks
    private UserInterestService userInterestService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .role(Role.USER)
                .build();
    }

    @Test
    void 관심분야를_추가_유지_삭제하여_전체교체한다() {
        UserInterest maintained = UserInterest.create(
                user,
                InterestType.CATEGORY,
                "일자리"
        );
        UserInterest removed = UserInterest.create(
                user,
                InterestType.NCS,
                "20010101"
        );

        when(userRepository.findByIdForUpdate(USER_ID))
                .thenReturn(Optional.of(user));
        when(userInterestRepository
                .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(USER_ID))
                .thenReturn(List.of(maintained, removed));

        UserInterestsUpdateRequest request = new UserInterestsUpdateRequest(
                List.of(
                        new UserInterestItemRequest(
                                InterestType.CATEGORY,
                                "  일자리  "
                        ),
                        new UserInterestItemRequest(
                                InterestType.SUB_CATEGORY,
                                "취업지원"
                        )
                )
        );

        UserInterestsResponse response =
                userInterestService.replaceInterests(USER_ID, request);

        verify(userInterestRepository).deleteAll(argThat(interests -> {
            List<? extends UserInterest> deleted = StreamSupport
                    .stream(interests.spliterator(), false)
                    .toList();

            return deleted.size() == 1
                    && deleted.getFirst() == removed;
        }));

        verify(userInterestRepository).saveAll(argThat(interests -> {
            List<? extends UserInterest> saved = StreamSupport
                    .stream(interests.spliterator(), false)
                    .toList();

            return saved.size() == 1
                    && saved.getFirst().getInterestType()
                    == InterestType.SUB_CATEGORY
                    && saved.getFirst().getInterestCode()
                    .equals("취업지원");
        }));

        assertEquals(2, response.interests().size());
        assertEquals(
                InterestType.CATEGORY,
                response.interests().get(0).type()
        );
        assertEquals(
                "일자리",
                response.interests().get(0).code()
        );
        assertEquals(
                InterestType.SUB_CATEGORY,
                response.interests().get(1).type()
        );
    }

    @Test
    void 빈_배열이면_기존_관심분야를_전부_해제한다() {
        UserInterest category = UserInterest.create(
                user,
                InterestType.CATEGORY,
                "주거"
        );
        UserInterest ncs = UserInterest.create(
                user,
                InterestType.NCS,
                "08020101"
        );

        when(userRepository.findByIdForUpdate(USER_ID))
                .thenReturn(Optional.of(user));
        when(userInterestRepository
                .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(USER_ID))
                .thenReturn(List.of(category, ncs));

        UserInterestsResponse response =
                userInterestService.replaceInterests(
                        USER_ID,
                        new UserInterestsUpdateRequest(List.of())
                );

        verify(userInterestRepository).deleteAll(argThat(interests ->
                StreamSupport.stream(
                        interests.spliterator(),
                        false
                ).count() == 2
        ));

        verify(userInterestRepository).saveAll(argThat(interests ->
                !interests.iterator().hasNext()
        ));

        assertTrue(response.interests().isEmpty());
    }

    @Test
    void 앞뒤_공백을_제거한_뒤_중복되는_관심분야는_거부한다() {
        when(userRepository.findByIdForUpdate(USER_ID))
                .thenReturn(Optional.of(user));

        UserInterestsUpdateRequest request = new UserInterestsUpdateRequest(
                List.of(
                        new UserInterestItemRequest(
                                InterestType.CATEGORY,
                                "일자리"
                        ),
                        new UserInterestItemRequest(
                                InterestType.CATEGORY,
                                "  일자리  "
                        )
                )
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userInterestService.replaceInterests(USER_ID, request)
        );

        assertEquals(
                GlobalErrorCode.INVALID_INPUT_VALUE,
                exception.getErrorCode()
        );
        verifyNoInteractions(userInterestRepository);
    }

    @Test
    void 관심분야가_30개를_초과하면_거부한다() {
        when(userRepository.findByIdForUpdate(USER_ID))
                .thenReturn(Optional.of(user));

        List<UserInterestItemRequest> interests = IntStream
                .rangeClosed(1, 31)
                .mapToObj(index -> new UserInterestItemRequest(
                        InterestType.CATEGORY,
                        "분야-" + index
                ))
                .toList();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userInterestService.replaceInterests(
                        USER_ID,
                        new UserInterestsUpdateRequest(interests)
                )
        );

        assertEquals(
                GlobalErrorCode.INVALID_INPUT_VALUE,
                exception.getErrorCode()
        );
        verifyNoInteractions(userInterestRepository);
    }

    @Test
    void 존재하지_않는_사용자는_관심분야를_변경할_수_없다() {
        when(userRepository.findByIdForUpdate(USER_ID))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userInterestService.replaceInterests(
                        USER_ID,
                        new UserInterestsUpdateRequest(List.of())
                )
        );

        assertEquals(
                UserErrorCode.USER_NOT_FOUND,
                exception.getErrorCode()
        );
        verifyNoInteractions(userInterestRepository);
    }

    @Test
    void 관심분야를_정렬된_순서로_조회한다() {
        when(userRepository.existsById(USER_ID))
                .thenReturn(true);
        when(userInterestRepository
                .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(USER_ID))
                .thenReturn(List.of(
                        UserInterest.create(
                                user,
                                InterestType.CATEGORY,
                                "일자리"
                        ),
                        UserInterest.create(
                                user,
                                InterestType.NCS,
                                "20010101"
                        )
                ));

        UserInterestsResponse response =
                userInterestService.getInterests(USER_ID);

        assertEquals(2, response.interests().size());
        assertEquals(
                InterestType.CATEGORY,
                response.interests().get(0).type()
        );
        assertEquals(
                InterestType.NCS,
                response.interests().get(1).type()
        );
    }

    @Test
    void 존재하지_않는_사용자의_관심분야는_조회할_수_없다() {
        when(userRepository.existsById(USER_ID))
                .thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userInterestService.getInterests(USER_ID)
        );

        assertEquals(
                UserErrorCode.USER_NOT_FOUND,
                exception.getErrorCode()
        );
        verify(
                userInterestRepository,
                never()
        ).findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(USER_ID);
    }
}