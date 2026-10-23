package com.team3.dataservice.global.security;

import com.team3.dataservice.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 내부 API 키 오류. 코드·메시지는 메인 서비스의 AuthErrorCode.INVALID_INTERNAL_API_KEY와 같다 */
@Getter
@RequiredArgsConstructor
public enum InternalApiErrorCode implements ErrorCode {
    INVALID_INTERNAL_API_KEY(HttpStatus.UNAUTHORIZED, "AUTH_008", "유효하지 않은 내부 API Key입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
