package com.team3.backend.preference.exception;

import com.team3.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 저장·숨김 API에서 사용하는 도메인 오류를 정의한다. */
@Getter
@RequiredArgsConstructor
public enum PreferenceErrorCode implements ErrorCode {

    OPPORTUNITY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "PREFERENCE_001",
            "공고를 찾을 수 없습니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;
}
