package com.team3.backend.outbox.domain.entity;

public enum OutboxStatus {
    PENDING, PUBLISHING, PUBLISHED, FAILED, SKIPPED
}
