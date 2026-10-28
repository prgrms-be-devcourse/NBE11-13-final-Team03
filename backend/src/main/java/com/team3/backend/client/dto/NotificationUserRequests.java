package com.team3.backend.client.dto;

import jakarta.validation.constraints.*;

/** Wire DTOs only. Notification entities/business rules remain in notification-service. */
public final class NotificationUserRequests {
    private NotificationUserRequests() { }
    public record Settings(@NotNull Boolean inAppEnabled, @NotNull Boolean pushEnabled) { }
    /** Browser request body. The login session is never accepted from the browser. */
    public record Endpoint(@NotBlank @Size(max = 2048) @Pattern(regexp = "[!-~]+") String fcmToken) {
        @Override public String toString() { return "Endpoint[fcmToken=REDACTED]"; }
    }
    /** Internal request to notification-service: the facade adds the authenticated session that registers the token. */
    public record EndpointRegistration(String fcmToken, String sessionId) {
        @Override public String toString() { return "EndpointRegistration[fcmToken=REDACTED, sessionId=" + sessionId + "]"; }
    }
}
