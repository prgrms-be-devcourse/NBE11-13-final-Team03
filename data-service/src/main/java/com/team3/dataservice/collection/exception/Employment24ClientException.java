package com.team3.dataservice.collection.exception;

public class Employment24ClientException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    public Employment24ClientException(String errorCode, boolean retryable) {
        super("고용24 API 조회 실패: " + errorCode);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
