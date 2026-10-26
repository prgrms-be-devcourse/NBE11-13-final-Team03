package com.team3.dataservice.outbox.service;

import com.team3.dataservice.outbox.config.CollectionNotificationPublisherProperties;
import com.team3.dataservice.outbox.publisher.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class OutboxPublicationWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublicationWorker.class);
    private final OutboxPublicationStore store;
    private final CollectionEventPublisher publisher;
    private final CollectionNotificationPublisherProperties properties;

    public OutboxPublicationWorker(OutboxPublicationStore store, CollectionEventPublisher publisher,
                                   CollectionNotificationPublisherProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${yon.collection.notification.publisher.poll-delay-ms:5000}", scheduler = "collectionOutboxScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runOnce() {
        store.recoverStale();
        for (int i = 0; i < properties.batchSize(); i++) {
            var next = store.claimNext();
            if (next.isEmpty()) break;
            var publication = next.orElseThrow();
            try {
                publisher.publish(publication);
            } catch (PublicationException failure) {
                if (failure.isConfiguration()) {
                    store.deferred(publication, failure.getMessage());
                    log.error("Collection publisher configuration error; stopping this batch: messageId={}, code={}",
                            publication.messageId(), failure.getMessage());
                    return;
                }
                store.failed(publication, failure.getMessage(), failure.isRetryable());
                log.warn("Collection publication failed: messageId={}, code={}", publication.messageId(), failure.getMessage());
                continue;
            } catch (RuntimeException failure) {
                store.failed(publication, "INTERNAL_PUBLISH_ERROR", true);
                log.warn("Collection publication failed: messageId={}, code=INTERNAL_PUBLISH_ERROR", publication.messageId());
                continue;
            }
            // A DB failure here must leave the lease for recovery, not pretend the SQS request never happened.
            boolean owned = store.published(publication);
            log.info("Collection publication acknowledged: messageId={}, leaseOwned={}", publication.messageId(), owned);
        }
    }
}
