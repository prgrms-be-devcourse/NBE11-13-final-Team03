package com.team3.backend.client;

import com.team3.backend.client.config.NotificationUserFeignConfig;
import com.team3.backend.client.dto.NotificationUserRequests.*;
import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "notification-user-api",
        url = "${yon.notification.facade.base-url:${NOTIFICATION_BASE_URL:http://notification-base-url-not-configured.invalid}}",
        configuration = NotificationUserFeignConfig.class)
public interface NotificationUserClient {
    @GetMapping("/internal/users/{userId}/notifications")
    Response list(@PathVariable("userId") long userId, @RequestParam(value = "beforeId", required = false) Long beforeId,
                  @RequestParam("size") int size);
    @GetMapping("/internal/users/{userId}/notifications/unread-count")
    Response unread(@PathVariable("userId") long userId);
    @PatchMapping("/internal/users/{userId}/notifications/{id}/read")
    Response read(@PathVariable("userId") long userId, @PathVariable("id") long id);
    @PatchMapping("/internal/users/{userId}/notifications/read-all")
    Response readAll(@PathVariable("userId") long userId);
    @PostMapping("/internal/users/{userId}/notifications/{id}/click")
    Response click(@PathVariable("userId") long userId, @PathVariable("id") long id);
    @GetMapping("/internal/users/{userId}/notification-settings")
    Response settings(@PathVariable("userId") long userId);
    @PutMapping("/internal/users/{userId}/notification-settings")
    Response settings(@PathVariable("userId") long userId, @RequestBody Settings body);
    @PostMapping("/internal/users/{userId}/notification-endpoints")
    Response endpoint(@PathVariable("userId") long userId, @RequestBody Endpoint body);
    @DeleteMapping("/internal/users/{userId}/notification-endpoints/{id}")
    Response removeEndpoint(@PathVariable("userId") long userId, @PathVariable("id") long id);
}
