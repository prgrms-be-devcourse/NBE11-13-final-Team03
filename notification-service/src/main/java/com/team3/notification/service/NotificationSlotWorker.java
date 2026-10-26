package com.team3.notification.service;

import com.team3.notification.config.NotificationPushProperties;
import com.team3.notification.push.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

public class NotificationSlotWorker {
    private static final Logger log=LoggerFactory.getLogger(NotificationSlotWorker.class);
    private final NotificationSlotStore store;
    private final PushSender sender;
    private final NotificationPushProperties push;
    private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    public NotificationSlotWorker(NotificationSlotStore store,PushSender sender,NotificationPushProperties push,Clock clock) {
        this.store=store;this.sender=sender;this.push=push;this.clock=clock;
    }
    @Scheduled(scheduler="notificationDeliveryScheduler",fixedDelayString="${yon.notification.push.poll-ms:5000}")
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void runOnce() {
        if (!running.compareAndSet(false,true)) return;
        try {
            store.expirePending();
            for (var ref:store.expiredBundles()) store.expire(ref);
            store.recoverStale();
            for (long user:store.candidates()) {
                try { store.build(user); }
                catch (RuntimeException failure) { log.warn("Push bundle build failed: userId={}, error={}",user,failure.getClass().getSimpleName()); }
            }
            for (int i=0;i<push.batchSize();i++) {
                var claim=store.claimNext(); if (claim.isEmpty()) break;
                var c=claim.orElseThrow(); var prepared=store.prepare(c); if (prepared.isEmpty()) continue;
                var message=prepared.orElseThrow(); String receipt;
                try {
                    if (!clock.instant().isBefore(message.expiresAt())) throw new PushFailure(PushFailure.Kind.PERMANENT,"PUSH_EXPIRED");
                    receipt=sender.send(message);
                    if (receipt==null || receipt.isBlank() || receipt.length()>500) throw new PushFailure(PushFailure.Kind.TRANSIENT,"FCM_INVALID_RECEIPT");
                } catch (PushFailure failure) {
                    store.failed(c,message,failure);
                    if (failure.kind()==PushFailure.Kind.CONFIGURATION) break;
                    continue;
                } catch (RuntimeException failure) {
                    store.failed(c,message,new PushFailure(PushFailure.Kind.TRANSIENT,"FCM_UNEXPECTED_FAILURE")); continue;
                }
                store.sent(c,receipt); // Completion failure leaves an attempted SENDING lease, never 'unattempted'.
            }
        } catch (RuntimeException failure) {
            log.error("Push slot poll failed: error={}",failure.getClass().getSimpleName());
        } finally { running.set(false); }
    }
}
