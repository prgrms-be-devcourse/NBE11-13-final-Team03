package com.team3.notification.api;

import com.team3.notification.service.NotificationTestPushService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationTestPushController {
    public static final String PATH = "/internal/notifications/test-push";
    public static final String AUTHORITY = "NOTIFICATION_TEST_PUSH";
    private final NotificationTestPushService service;

    public NotificationTestPushController(NotificationTestPushService service) { this.service = service; }

    public record Request(@NotNull @Positive Long userId, @NotNull @Positive Long notificationId,
                          @NotNull @Positive Long endpointId) { }

    @PostMapping(PATH)
    public NotificationTestPushService.Result send(@Valid @RequestBody Request request) {
        return service.send(request.userId(), request.notificationId(), request.endpointId());
    }
}
