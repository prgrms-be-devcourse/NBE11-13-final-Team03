package com.team3.backend.behavior.retention.admin;

import com.team3.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RetentionAdminErrorCode implements ErrorCode {
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "RETENTION_001", "관리자 권한이 필요합니다."),
    EXECUTE_DISABLED(HttpStatus.FORBIDDEN, "RETENTION_002", "관리자 API의 행동 로그 보관 정리 실행이 비활성화되어 있습니다."),
    PLAN_CHANGED(HttpStatus.CONFLICT, "RETENTION_003", "미리보기 이후 보관 기준 또는 정리 대상이 바뀌었습니다. 다시 미리보기 후 확인하세요."),
    RUN_IN_PROGRESS(HttpStatus.CONFLICT, "RETENTION_004", "다른 보관 정리가 진행 중입니다. 잠시 후 다시 미리보기 후 실행하세요."),
    NOT_RUNNABLE(HttpStatus.CONFLICT, "RETENTION_005", "현재 DB 상태에서는 보관 정리를 실행할 수 없습니다. 미리보기 상태를 확인하세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
