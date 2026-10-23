package com.team3.notification.service;

/** Same immutable messageId was reused with a different known message body. Never acknowledge as duplicate. */
public class MessageIdentityConflictException extends IllegalArgumentException {
    public MessageIdentityConflictException() {
        super("messageId was reused with a different request");
    }
}
