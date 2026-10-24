package com.team3.notification.push;

public class PushFailure extends RuntimeException {
    public enum Kind { TRANSIENT, INVALID_TOKEN, PERMANENT, CONFIGURATION }
    private final Kind kind;
    public PushFailure(Kind kind, String safeCode) { super(safeCode); this.kind = kind; }
    public Kind kind() { return kind; }
}
