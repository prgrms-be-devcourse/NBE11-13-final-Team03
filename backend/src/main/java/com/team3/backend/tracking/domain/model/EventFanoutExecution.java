package com.team3.backend.tracking.domain.model;

import java.util.UUID;
import java.util.Objects;

public record EventFanoutExecution(Long eventId, UUID leaseToken) {
    public EventFanoutExecution {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(leaseToken);
    }
}
