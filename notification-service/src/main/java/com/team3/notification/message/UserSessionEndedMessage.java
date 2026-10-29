package com.team3.notification.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

/** Logout control envelope: identifies one login session whose push Endpoints must stop receiving pushes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSessionEndedMessage(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull UUID messageId,
        @NotNull @Pattern(regexp = "USER_SESSION_ENDED") String eventType,
        @NotNull UUID sourceEventId,
        @NotNull UUID correlationId,
        @NotNull UUID causationId,
        @NotNull @Positive Long userId,
        @NotBlank @Size(max = 36) @Pattern(regexp = "[!-~]+") String sessionId,
        @NotNull Instant occurredAt
) { }
