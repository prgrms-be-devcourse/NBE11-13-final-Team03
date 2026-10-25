package com.team3.backend.tracking.domain.entity;

public enum OpportunityVersionTrackingStatus {
    BASELINE_SKIPPED,
    NO_CHANGE,
    NORMALIZATION_VERSION_CHANGED,
    /** Non-terminal: event assessment must finish before this version can be considered processed. */
    READY_FOR_EVENT_ANALYSIS,
    /** Eligibility work is durable and the sequence may advance; unresolved deadline evidence is retained separately. */
    PROCESSED_WITH_DEFERRED_DEADLINE,
    PROCESSED
}
