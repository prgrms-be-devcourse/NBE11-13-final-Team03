package com.team3.notification.api;

import com.team3.notification.api.NotificationUserDtos.*;
import com.team3.notification.service.NotificationUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** userId is supplied by the authenticated Application facade, never directly by a browser. */
@RestController
@RequestMapping("/internal/users/{userId}")
public class NotificationUserController {
    private final NotificationUserService service;
    public NotificationUserController(NotificationUserService service) { this.service = service; }

    @GetMapping("/notifications")
    public Page list(@PathVariable @Positive long userId, @RequestParam(required = false) Long beforeId,
                     @RequestParam(defaultValue = "20") int size) { return service.list(userId, beforeId, size); }
    @GetMapping("/notifications/unread-count")
    public UnreadCount unread(@PathVariable @Positive long userId) { return service.unreadCount(userId); }
    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<Void> read(@PathVariable @Positive long userId, @PathVariable @Positive long id) {
        service.read(userId, id); return ResponseEntity.noContent().build();
    }
    @PatchMapping("/notifications/read-all")
    public ResponseEntity<Void> readAll(@PathVariable @Positive long userId) {
        service.readAll(userId); return ResponseEntity.noContent().build();
    }
    @PostMapping("/notifications/{id}/click")
    public ResponseEntity<Void> click(@PathVariable @Positive long userId, @PathVariable @Positive long id) {
        service.click(userId, id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/notification-settings")
    public Settings settings(@PathVariable @Positive long userId) { return service.settings(userId); }
    @PutMapping("/notification-settings")
    public Settings settings(@PathVariable @Positive long userId, @Valid @RequestBody SettingsRequest body) {
        return service.updateSettings(userId, body);
    }
    @PostMapping("/notification-endpoints")
    public Endpoint endpoint(@PathVariable @Positive long userId, @Valid @RequestBody EndpointRequest body) {
        return service.registerEndpoint(userId, body);
    }
    @DeleteMapping("/notification-endpoints/{id}")
    public ResponseEntity<Void> remove(@PathVariable @Positive long userId, @PathVariable @Positive long id) {
        service.deactivateEndpoint(userId, id); return ResponseEntity.noContent().build();
    }
}
