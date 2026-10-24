package com.team3.notification.api;

import com.team3.notification.domain.entity.Notification;
import com.team3.notification.message.NotificationType;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class NotificationUserDtos {
    private NotificationUserDtos() { }
    public record Item(Long id, Long opportunityId, NotificationType type, String title, String content,
                       String link, Instant createdAt, Instant readAt, Instant clickedAt) {
        public static Item from(Notification n) {
            return new Item(n.getId(), n.getOpportunityId(), n.getType(), n.getTitle(), n.getContent(),
                    n.getLink(), n.getCreatedAt(), n.getReadAt(), n.getClickedAt());
        }
    }
    public record Page(List<Item> items, Long nextCursor, boolean hasNext) { }
    public record UnreadCount(boolean available, Long unreadCount) { }
    public record Settings(boolean inAppEnabled, boolean pushEnabled, Instant pushConsentAt, Instant updatedAt) { }
    public record SettingsRequest(@NotNull Boolean inAppEnabled, @NotNull Boolean pushEnabled) { }
    public record EndpointRequest(@NotBlank @Size(max = 2048) @Pattern(regexp = "[!-~]+") String fcmToken) {
        @Override public String toString() { return "EndpointRequest[fcmToken=REDACTED]"; }
    }
    public record Endpoint(Long id, boolean active) { }
}
