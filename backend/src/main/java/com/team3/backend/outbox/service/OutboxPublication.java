package com.team3.backend.outbox.service;

import java.util.UUID;

public record OutboxPublication(long id, UUID messageId, UUID leaseToken, String json) { }
