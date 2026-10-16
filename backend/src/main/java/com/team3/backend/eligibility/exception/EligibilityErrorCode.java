package com.team3.backend.eligibility.exception;

import com.team3.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum EligibilityErrorCode implements ErrorCode {

    PROFILE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "ELIGIBILITY_001",
            "판정 조건을 먼저 등록해야 합니다."
    ),
    OPPORTUNITY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "ELIGIBILITY_002",
            "존재하지 않는 기회입니다."
    ),
    NOT_NORMALIZED(
            HttpStatus.CONFLICT,
            "ELIGIBILITY_003",
            "아직 정규화되지 않은 기회입니다."
    ),
    INVALID_PROFILE_VALUE(
            HttpStatus.BAD_REQUEST,
            "ELIGIBILITY_004",
            "판정 조건 입력값이 올바르지 않습니다."
    ),
    INVALID_REGION_CODE(
            HttpStatus.BAD_REQUEST,
            "ELIGIBILITY_005",
            "사용할 수 없는 지역 코드입니다."
    ),
    INVALID_PAGE_REQUEST(
            HttpStatus.BAD_REQUEST,
            "ELIGIBILITY_006",
            "페이지 요청값이 올바르지 않습니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;
}
