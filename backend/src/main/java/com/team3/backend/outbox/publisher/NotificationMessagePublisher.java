package com.team3.backend.outbox.publisher;

import com.team3.backend.outbox.service.OutboxPublication;

/** Exactly one transport implementation is selected; SQS is a later stage. */
public interface NotificationMessagePublisher {
    void publish(OutboxPublication publication);
}
