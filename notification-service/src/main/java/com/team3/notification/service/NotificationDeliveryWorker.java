package com.team3.notification.service;

import com.team3.notification.config.NotificationPushProperties;
import com.team3.notification.push.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class NotificationDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);
    private final NotificationDeliveryStore store;
    private final PushSender sender;
    private final NotificationPushProperties properties;
    public NotificationDeliveryWorker(NotificationDeliveryStore store, PushSender sender, NotificationPushProperties properties) {
        this.store = store; this.sender = sender; this.properties = properties;
    }

    @Scheduled(scheduler = "notificationDeliveryScheduler", fixedDelayString = "${yon.notification.push.poll-ms:5000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runOnce() {
        store.recoverStale();
        for (int i = 0; i < properties.batchSize(); i++) {
            var claim = store.claimNext();
            if (claim.isEmpty()) return;
            var prepared = store.prepare(claim.orElseThrow());
            if (prepared.isEmpty()) continue;
            var message = prepared.orElseThrow();
            String receipt;
            try {
                receipt = sender.send(message);
                if (receipt == null || receipt.isBlank() || receipt.length() > 500) {
                    throw new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_INVALID_RECEIPT");
                }
            } catch (PushFailure failure) {
                store.failed(claim.orElseThrow(), message, failure);
                log.warn("Push rejected: deliveryId={}, correlationId={}, reason={}", message.deliveryId(), message.correlationId(), failure.getMessage());
                if (failure.kind() == PushFailure.Kind.CONFIGURATION) return;
                continue;
            } catch (RuntimeException failure) {
                store.failed(claim.orElseThrow(), message, new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_UNEXPECTED_FAILURE"));
                continue;
            }
            // DB completion failure leaves SENDING for lease recovery; never pretend that external FCM rolled back.
            boolean recorded = store.sent(claim.orElseThrow(), receipt);
            log.info("Push accepted: deliveryId={}, correlationId={}, recorded={}", message.deliveryId(), message.correlationId(), recorded);
        }
    }
}
