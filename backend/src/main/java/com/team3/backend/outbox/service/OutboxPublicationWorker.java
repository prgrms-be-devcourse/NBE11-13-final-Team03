package com.team3.backend.outbox.service;

import com.team3.backend.outbox.config.NotificationPublisherProperties;
import com.team3.backend.outbox.publisher.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class OutboxPublicationWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublicationWorker.class);
    private final OutboxPublicationStore store;
    private final NotificationMessagePublisher publisher;
    private final NotificationPublisherProperties properties;

    public OutboxPublicationWorker(OutboxPublicationStore store, NotificationMessagePublisher publisher,
                                   NotificationPublisherProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${yon.notification.publisher.poll-delay-ms:5000}", scheduler = "notificationOutboxScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runOnce() {
        store.recoverStale();
        for (int i = 0; i < properties.batchSize(); i++) {
            var next = store.claimNext();
            if (next.isEmpty()) break;
            var publication = next.orElseThrow();
            try {
                publisher.publish(publication);
            } catch (NotificationPublicationException failure) {
                store.failed(publication, failure.getMessage(), failure.isRetryable());
                log.warn("Notification publication failed: messageId={}, code={}", publication.messageId(), failure.getMessage());
                continue;
            } catch (RuntimeException failure) {
                store.failed(publication, "INTERNAL_PUBLISH_ERROR", true);
                log.warn("Notification publication failed: messageId={}, code=INTERNAL_PUBLISH_ERROR", publication.messageId());
                continue;
            }
            // A DB failure here must leave the lease for recovery, not pretend the HTTP request never happened.
            boolean owned = store.published(publication);
            log.info("Notification publication acknowledged: messageId={}, leaseOwned={}", publication.messageId(), owned);
        }
    }
}
