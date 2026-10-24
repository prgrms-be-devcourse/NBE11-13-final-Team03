package com.team3.backend.client;

import com.team3.backend.client.config.NotificationFacadeProperties;
import feign.Response;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Bounded synchronous transport only: no notification persistence or business processing. */
@Component
public class NotificationUserRelay {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final NotificationFacadeProperties properties;
    private final ObjectMapper mapper;
    private final Semaphore slots;

    public NotificationUserRelay(NotificationFacadeProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.slots = new Semaphore(properties.maxConcurrentRequests());
    }

    public ResponseEntity<?> call(Supplier<Response> call, int successStatus, boolean unread) {
        if (!properties.enabled() || !slots.tryAcquire()) return unavailable(unread);
        try (var response = call.get()) {
            int status = response.status();
            // These statuses are explicit user API contract errors; never relay remote diagnostics.
            if (status == 400 || status == 404 || status == 409) {
                return ResponseEntity.status(status).body(Map.of("code", switch (status) {
                    case 400 -> "INVALID_NOTIFICATION_REQUEST";
                    case 404 -> "NOTIFICATION_RESOURCE_NOT_FOUND";
                    default -> "NOTIFICATION_ENDPOINT_CONFLICT";
                }));
            }
            // Remote 401/403 indicate service authentication misconfiguration, not browser authentication.
            if (status != successStatus) return unavailable(unread);
            if (successStatus == 204) return ResponseEntity.noContent().build();
            if (response.body() == null) return unavailable(unread);
            byte[] bytes;
            try (var stream = response.body().asInputStream()) {
                bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) return unavailable(unread);
            var json = mapper.readTree(bytes);
            if (json == null || !json.isObject()) return unavailable(unread);
            if (unread && (!json.path("available").isBoolean() || !json.path("available").asBoolean()
                    || !json.path("unreadCount").isIntegralNumber() || !json.path("unreadCount").canConvertToLong()
                    || json.path("unreadCount").asLong() < 0)) return unavailable(true);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(bytes);
        } catch (RuntimeException | IOException failure) {
            // Never log exception URLs/bodies: Endpoint calls contain a confidential token.
            return unavailable(unread);
        } finally {
            slots.release();
        }
    }

    private ResponseEntity<?> unavailable(boolean unread) {
        if (unread) return ResponseEntity.ok(new UnreadUnavailable(false, null));
        return ResponseEntity.status(503).body(Map.of("code", "NOTIFICATION_UNAVAILABLE"));
    }
    public record UnreadUnavailable(boolean available, Long unreadCount) { }
}
