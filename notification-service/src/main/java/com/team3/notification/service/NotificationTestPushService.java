package com.team3.notification.service;

import com.team3.notification.config.NotificationPushProperties;
import com.team3.notification.config.NotificationTestPushProperties;
import com.team3.notification.push.PushFailure;
import com.team3.notification.push.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class NotificationTestPushService {
    private static final Logger log = LoggerFactory.getLogger(NotificationTestPushService.class);
    private final NotificationTestPushProperties test;
    private final NotificationPushProperties push;
    private final NotificationTestPushStore store;
    private final ObjectProvider<PushSender> senders;

    public NotificationTestPushService(NotificationTestPushProperties test, NotificationPushProperties push,
            NotificationTestPushStore store, ObjectProvider<PushSender> senders) {
        this.test = test; this.push = push; this.store = store; this.senders = senders;
    }

    public record Result(UUID requestId, long notificationId, long endpointId, String status, String fcmMessageId) { }

    /** Deliberate one-shot send: bypasses slot scheduling/counting and never retries automatically. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result send(long userId, long notificationId, long endpointId) {
        if (!test.enabled() || !push.enabled()) throw new NotificationUserApiException(503, "TEST_PUSH_DISABLED");
        if (!test.allowedUserIds().contains(userId)) throw new NotificationUserApiException(403, "TEST_PUSH_USER_NOT_ALLOWED");
        var sender = senders.getIfAvailable();
        if (sender == null) throw new NotificationUserApiException(503, "TEST_PUSH_DISABLED");
        var requestId = UUID.randomUUID();
        var message = store.prepare(userId, notificationId, endpointId, requestId);
        log.info("Test push attempt: requestId={}, userId={}, notificationId={}, endpointId={}",
                requestId, userId, notificationId, endpointId);
        try {
            String receipt = sender.send(message);
            if (receipt == null || receipt.isBlank() || receipt.length() > 500) {
                throw new PushFailure(PushFailure.Kind.TRANSIENT, "FCM_INVALID_RECEIPT");
            }
            log.info("Test push accepted: requestId={}, userId={}, notificationId={}, endpointId={}, fcmMessageId={}",
                    requestId, userId, notificationId, endpointId, receipt);
            return new Result(requestId, notificationId, endpointId, "FCM_ACCEPTED", receipt);
        } catch (PushFailure failure) {
            log.warn("Test push failed: requestId={}, userId={}, notificationId={}, endpointId={}, kind={}",
                    requestId, userId, notificationId, endpointId, failure.kind());
            if (failure.kind() == PushFailure.Kind.INVALID_TOKEN) {
                store.deactivateInvalidToken(message);
                throw new NotificationUserApiException(409, "FCM_UNREGISTERED");
            }
            throw new NotificationUserApiException(502, "TEST_PUSH_FCM_" + failure.kind().name());
        } catch (RuntimeException failure) {
            log.error("Test push unexpected failure: requestId={}, userId={}, notificationId={}, endpointId={}, error={}",
                    requestId, userId, notificationId, endpointId, failure.getClass().getSimpleName());
            throw new NotificationUserApiException(503, "TEST_PUSH_UNAVAILABLE");
        }
    }
}
