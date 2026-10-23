package com.team3.dataservice.collection.exception;

import com.team3.dataservice.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CollectionErrorCode implements ErrorCode {
    EXECUTOR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COLLECTION_001", "최신화 실행기를 사용할 수 없습니다."),
    INVALID_BODY(HttpStatus.BAD_REQUEST, "COLLECTION_002", "요청 JSON의 필수값과 형식을 확인하세요."),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "COLLECTION_003", "수집 요청 값을 확인하세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COLLECTION_004", "수집 실행·출처 또는 원본 레코드를 찾을 수 없습니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "COLLECTION_005", "현재 상태에서는 수집 요청을 처리할 수 없습니다."),
    STORAGE_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR, "COLLECTION_006", "원본 데이터 저장소 처리에 실패했습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "COLLECTION_007", "관리자 권한이 필요합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
