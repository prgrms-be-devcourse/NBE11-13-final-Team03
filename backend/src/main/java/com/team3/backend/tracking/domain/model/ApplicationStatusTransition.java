package com.team3.backend.tracking.domain.model;

import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;

import java.time.LocalDate;

public record ApplicationStatusTransition(
        LocalDate eventDate,
        ApplicationStatus previousStatus,
        ApplicationStatus currentStatus,
        OpportunityEventType eventType
) {
}