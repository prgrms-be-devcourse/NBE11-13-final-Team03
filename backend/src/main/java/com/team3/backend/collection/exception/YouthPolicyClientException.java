package com.team3.backend.collection.exception;

public class YouthPolicyClientException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public YouthPolicyClientException(String errorCode, boolean retryable) {
        super("온통청년 API 조회 실패: " + errorCode);
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
