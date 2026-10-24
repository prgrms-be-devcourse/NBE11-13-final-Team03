package com.team3.notification.service;

public class NotificationUserApiException extends RuntimeException {
    private final int status;
    private final String code;
    public NotificationUserApiException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
