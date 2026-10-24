package com.team3.backend.client.dto;

import jakarta.validation.constraints.*;

/** Wire DTOs only. Notification entities/business rules remain in notification-service. */
public final class NotificationUserRequests {
    private NotificationUserRequests() { }
    public record Settings(@NotNull Boolean inAppEnabled, @NotNull Boolean pushEnabled) { }
    public record Endpoint(@NotBlank @Size(max = 2048) @Pattern(regexp = "[!-~]+") String fcmToken) {
        @Override public String toString() { return "Endpoint[fcmToken=REDACTED]"; }
    }
}
