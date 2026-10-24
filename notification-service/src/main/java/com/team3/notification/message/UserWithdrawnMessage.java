package com.team3.notification.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

/** Control envelope: deliberately has no title, opportunity, channels or expiry requirement. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserWithdrawnMessage(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull UUID messageId,
        @NotNull @Pattern(regexp = "USER_WITHDRAWN") String eventType,
        @NotNull UUID sourceEventId,
        @NotNull UUID correlationId,
        @NotNull UUID causationId,
        @NotNull @Positive Long userId,
        @NotNull Instant occurredAt
) { }
