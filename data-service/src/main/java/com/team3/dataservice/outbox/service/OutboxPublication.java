package com.team3.dataservice.outbox.service;

import java.util.UUID;

public record OutboxPublication(long id, UUID messageId, UUID leaseToken, String json) { }
