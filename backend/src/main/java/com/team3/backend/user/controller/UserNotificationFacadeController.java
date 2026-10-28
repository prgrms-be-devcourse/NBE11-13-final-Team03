package com.team3.backend.user.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.client.NotificationUserClient;
import com.team3.backend.client.NotificationUserRelay;
import static com.team3.backend.client.NotificationUserRelay.ContractError.*;
import com.team3.backend.client.dto.NotificationUserRequests.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Authentication/HTTP facade only; deliberately outside backend.notification (forbidden boundary). */
@RestController
@RequestMapping("/api")
public class UserNotificationFacadeController {
    private final NotificationUserClient client;
    private final NotificationUserRelay relay;
    public UserNotificationFacadeController(NotificationUserClient client, NotificationUserRelay relay) {
        this.client = client; this.relay = relay;
    }
    @GetMapping("/notifications")
    public ResponseEntity<?> list(@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required = false) @Positive Long beforeId, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return relay.call(() -> client.list(user.getUserId(), beforeId, size), 200, false, USER_WITHDRAWN);
    }
    @GetMapping("/notifications/unread-count")
    public ResponseEntity<?> unread(@AuthenticationPrincipal CustomUserDetails user) {
        return relay.call(() -> client.unread(user.getUserId()), 200, true, USER_WITHDRAWN);
    }
    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<?> read(@AuthenticationPrincipal CustomUserDetails user, @PathVariable @Positive long id) {
        return relay.call(() -> client.read(user.getUserId(), id), 204, false, NOT_FOUND, USER_WITHDRAWN);
    }
    @PatchMapping("/notifications/read-all")
    public ResponseEntity<?> readAll(@AuthenticationPrincipal CustomUserDetails user) {
        return relay.call(() -> client.readAll(user.getUserId()), 204, false, USER_WITHDRAWN);
    }
    @PostMapping("/notifications/{id}/click")
    public ResponseEntity<?> click(@AuthenticationPrincipal CustomUserDetails user, @PathVariable @Positive long id) {
        return relay.call(() -> client.click(user.getUserId(), id), 204, false, NOT_FOUND, USER_WITHDRAWN);
    }
    @GetMapping("/users/me/notification-settings")
    public ResponseEntity<?> settings(@AuthenticationPrincipal CustomUserDetails user) {
        return relay.call(() -> client.settings(user.getUserId()), 200, false, USER_WITHDRAWN);
    }
    @PutMapping("/users/me/notification-settings")
    public ResponseEntity<?> settings(@AuthenticationPrincipal CustomUserDetails user, @Valid @RequestBody Settings body) {
        return relay.call(() -> client.settings(user.getUserId(), body), 200, false, USER_WITHDRAWN);
    }
    @PostMapping("/users/me/notification-endpoints")
    public ResponseEntity<?> endpoint(@AuthenticationPrincipal CustomUserDetails user, @Valid @RequestBody Endpoint body) {
        // The session comes from the access token, so a logout of this session can deactivate exactly this Endpoint.
        var registration = new EndpointRegistration(body.fcmToken(), user.getSessionId());
        return relay.call(() -> client.endpoint(user.getUserId(), registration), 200, false, ENDPOINT_CONFLICT, USER_WITHDRAWN);
    }
    @DeleteMapping("/users/me/notification-endpoints/{id}")
    public ResponseEntity<?> remove(@AuthenticationPrincipal CustomUserDetails user, @PathVariable @Positive long id) {
        return relay.call(() -> client.removeEndpoint(user.getUserId(), id), 204, false, NOT_FOUND, USER_WITHDRAWN);
    }
}
