package com.team3.dataservice.outbox.publisher;

import com.team3.dataservice.outbox.service.OutboxPublication;

/** Sends the stored collection envelope to the administrator queue. */
public interface CollectionEventPublisher {
    void publish(OutboxPublication publication);
}
