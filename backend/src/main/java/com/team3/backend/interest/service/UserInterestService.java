package com.team3.backend.interest.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.domain.entity.UserInterest;
import com.team3.backend.interest.domain.repository.UserInterestRepository;
import com.team3.backend.interest.dto.UserInterestItemRequest;
import com.team3.backend.interest.dto.UserInterestResponse;
import com.team3.backend.interest.dto.UserInterestsResponse;
import com.team3.backend.interest.dto.UserInterestsUpdateRequest;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.exception.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserInterestService {

    private static final int MAX_INTEREST_COUNT = 30;
    private static final int MAX_CODE_LENGTH = 100;

    private final UserRepository userRepository;
    private final UserInterestRepository userInterestRepository;

    @Transactional(readOnly = true)
    public UserInterestsResponse getInterests(Long userId) {
        validateUserExists(userId);

        List<UserInterestResponse> interests = userInterestRepository
                .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(userId)
                .stream()
                .map(UserInterestResponse::from)
                .toList();

        return new UserInterestsResponse(interests);
    }

    /**
     * 요청받은 목록을 사용자의 최종 관심 분야 상태로 반영한다.
     *
     * 기존 값과 요청 값을 비교하여:
     * - 요청에 그대로 남아 있는 값은 유지
     * - 요청에서 빠진 값은 삭제
     * - 새로 추가된 값만 저장
     */
    @Transactional
    public UserInterestsResponse replaceInterests(
            Long userId,
            UserInterestsUpdateRequest request
    ) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() ->
                        new BusinessException(UserErrorCode.USER_NOT_FOUND)
                );

        Set<InterestKey> requestedKeys =
                normalizeAndValidate(request);

        List<UserInterest> existingInterests =
                userInterestRepository
                        .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(
                                userId
                        );

        Map<InterestKey, UserInterest> existingByKey =
                existingInterests.stream()
                        .collect(Collectors.toMap(
                                interest -> new InterestKey(
                                        interest.getInterestType(),
                                        interest.getInterestCode()
                                ),
                                Function.identity()
                        ));

        List<UserInterest> interestsToDelete =
                existingByKey.entrySet()
                        .stream()
                        .filter(entry ->
                                !requestedKeys.contains(entry.getKey())
                        )
                        .map(Map.Entry::getValue)
                        .toList();

        List<UserInterest> interestsToAdd =
                requestedKeys.stream()
                        .filter(key ->
                                !existingByKey.containsKey(key)
                        )
                        .map(key -> UserInterest.create(
                                user,
                                key.type(),
                                key.code()
                        ))
                        .toList();

        userInterestRepository.deleteAll(interestsToDelete);
        userInterestRepository.saveAll(interestsToAdd);

        /*
         * PUT 응답과 GET 응답이 같은 DB 정렬 기준을 사용하도록
         * 저장된 최종 상태를 Repository에서 다시 조회한다.
         *
         * 현재 트랜잭션의 flush mode가 AUTO이므로 조회 쿼리 실행 전에
         * 삭제 및 추가 변경사항이 DB에 반영된다.
         */
        List<UserInterestResponse> response =
                userInterestRepository
                        .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(
                                userId
                        )
                        .stream()
                        .map(UserInterestResponse::from)
                        .toList();

        return new UserInterestsResponse(response);
    }

    private Set<InterestKey> normalizeAndValidate(
            UserInterestsUpdateRequest request
    ) {
        if (request == null
                || request.interests() == null
                || request.interests().size() > MAX_INTEREST_COUNT) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }

        Set<InterestKey> normalized = new HashSet<>();

        for (UserInterestItemRequest item : request.interests()) {
            if (item == null || item.type() == null || item.code() == null) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
            }

            String code = item.code().strip();

            if (code.isEmpty() || code.length() > MAX_CODE_LENGTH) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
            }

            InterestKey key = new InterestKey(item.type(), code);

            if (!normalized.add(key)) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
            }
        }

        return normalized;
    }

    private void validateUserExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
    }

    private record InterestKey(
            InterestType type,
            String code
    ) {
    }
}