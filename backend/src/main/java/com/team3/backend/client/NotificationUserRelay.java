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

    /** Remote statuses a specific call may surface to the user; every other non-success status is an outage. */
    public enum ContractError {
        NOT_FOUND(404, "NOTIFICATION_RESOURCE_NOT_FOUND"),
        ENDPOINT_CONFLICT(409, "NOTIFICATION_ENDPOINT_CONFLICT");
        private final int status;
        private final String code;
        ContractError(int status, String code) { this.status = status; this.code = code; }
    }

    public ResponseEntity<?> call(Supplier<Response> call, int successStatus, boolean unread, ContractError... allowed) {
        if (!properties.enabled() || !slots.tryAcquire()) return unavailable(unread);
        try (var response = call.get()) {
            int status = response.status();
            // Only the contract errors this call can legitimately produce are relayed (fixed code, no remote diagnostics).
            // A 404 on a call without owned resources (settings, list, counts) means a routing/deployment error, not "not found".
            for (var error : allowed) {
                if (error.status == status) return ResponseEntity.status(status).body(Map.of("code", error.code));
            }
            // Remote 400/401/403 indicate facade/contract/key misconfiguration, not a browser request error.
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
