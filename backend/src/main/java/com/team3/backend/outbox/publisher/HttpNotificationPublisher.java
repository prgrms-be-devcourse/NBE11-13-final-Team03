package com.team3.backend.outbox.publisher;

import com.team3.backend.outbox.config.NotificationPublisherProperties;
import com.team3.backend.outbox.service.OutboxPublication;
import com.team3.backend.client.NotificationClient;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class HttpNotificationPublisher implements NotificationMessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(HttpNotificationPublisher.class);
    /** Receipts a notification request may get; WITHDRAWN is its normal suppression by a Tombstone. */
    private static final Set<String> NOTIFICATION_RECEIPTS = Set.of("CREATED", "DUPLICATE_EVENT", "EXPIRED", "CHANNEL_DISABLED", "WITHDRAWN");
    private static final Set<String> EVENT_TYPES = Set.of("NOTIFICATION_REQUESTED", "USER_WITHDRAWN", "USER_SESSION_ENDED");
    /** A control message is published only when Notification acknowledged it with its own receipt status. */
    private static final Map<String, String> CONTROL_RECEIPTS = Map.of("USER_WITHDRAWN", "WITHDRAWN",
            "USER_SESSION_ENDED", "SESSION_ENDED");
    private final NotificationClient client;
    private final NotificationPublisherProperties properties;
    private final ObjectMapper mapper;

    public HttpNotificationPublisher(NotificationClient client, NotificationPublisherProperties properties, ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public void publish(OutboxPublication publication) {
        String correlationId;
        String eventType;
        try {
            var envelope = mapper.readTree(publication.json());
            if (!publication.messageId().toString().equals(envelope.path("messageId").asText())) {
                throw new IllegalArgumentException("identity mismatch");
            }
            correlationId = UUID.fromString(envelope.path("correlationId").asText()).toString();
            eventType = envelope.path("eventType").asText();
            if (!EVENT_TYPES.contains(eventType)) {
                throw new IllegalArgumentException("unsupported event type");
            }
        } catch (RuntimeException failure) {
            throw new NotificationPublicationException("INVALID_OUTBOX_ENVELOPE", false);
        }
        try (var response = client.send(properties.apiKey(), correlationId, publication.json())) {
            int status = response.status();
            if (status != 200) throw httpFailure(status);
            if (response.body() == null) throw new NotificationPublicationException("INVALID_RECEIPT", true);
            var bytes = response.body().asInputStream().readNBytes(16385);
            if (bytes.length > 16384) throw new NotificationPublicationException("INVALID_RECEIPT", true);
            try {
                var receipt = mapper.readTree(bytes);
                String result = receipt.path("status").asText();
                String control = CONTROL_RECEIPTS.get(eventType);
                boolean accepted = control != null ? control.equals(result) : NOTIFICATION_RECEIPTS.contains(result);
                if (!publication.messageId().toString().equals(receipt.path("messageId").asText())
                        || !accepted || !receipt.path("duplicateMessage").isBoolean()) {
                    throw new IllegalArgumentException("receipt mismatch");
                }
                log.info("Notification HTTP receipt: correlationId={}, messageId={}, result={}",
                        correlationId, publication.messageId(), result);
            } catch (RuntimeException failure) {
                throw new NotificationPublicationException("INVALID_RECEIPT", true);
            }
        } catch (FeignException failure) {
            int status = failure.status();
            throw status < 0 ? new NotificationPublicationException("HTTP_IO_FAILURE", true) : httpFailure(status);
        } catch (IOException failure) {
            throw new NotificationPublicationException("HTTP_IO_FAILURE", true);
        }
    }

    private static NotificationPublicationException httpFailure(int status) {
        String code = "HTTP_" + status;
        if (status == 401 || status == 403 || status == 404 || (status >= 300 && status < 400)) {
            return NotificationPublicationException.configuration(code);
        }
        return new NotificationPublicationException(code, status == 408 || status == 429 || status >= 500);
    }
}
