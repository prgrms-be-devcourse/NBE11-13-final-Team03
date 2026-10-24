package com.team3.notification.push;

import java.time.Instant;
import java.util.UUID;

/** Immutable preflight snapshot; never log the token or notification text. */
public record PushMessage(long deliveryId, long notificationId, long userId, long endpointId, UUID leaseToken,
        String fcmToken, String title, String content, String link, Instant expiresAt, UUID messageId, UUID correlationId) {
    @Override public String toString() {
        return "PushMessage[deliveryId=" + deliveryId + ", messageId=" + messageId + ", credentials=REDACTED]";
    }
}
