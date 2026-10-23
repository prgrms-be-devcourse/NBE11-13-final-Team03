package com.team3.notification.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationRequestedMessage(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull UUID messageId,
        @NotNull @Pattern(regexp = "NOTIFICATION_REQUESTED") String eventType,
        @NotNull UUID sourceEventId,
        @NotNull UUID correlationId,
        @NotNull UUID causationId,
        @NotNull @Positive Long userId,
        @NotNull @Positive Long opportunityId,
        @NotNull @Positive Long opportunityVersionId,
        @NotNull NotificationType type,
        @NotEmpty Set<@NotNull NotificationChannel> channels,
        @NotBlank @Size(max = 500) String title,
        @NotBlank String content,
        @NotBlank @Size(max = 2000) String link,
        @NotNull Instant occurredAt,
        @NotNull Instant expiresAt,
        @NotNull Map<String, Object> payload
) {
    public NotificationRequestedMessage {
        if (channels != null) {
            channels = channels.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(channels));
        }
        if (payload != null) payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
