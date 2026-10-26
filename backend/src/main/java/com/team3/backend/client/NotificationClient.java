package com.team3.backend.client;

import com.team3.backend.client.config.NotificationFeignConfig;
import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** Only JSON crosses the project boundary; no Notification Java types are imported. */
@FeignClient(value = "notification-service",
        url = "${yon.notification.publisher.base-url:${NOTIFICATION_BASE_URL:http://notification-base-url-not-configured.invalid}}",
        configuration = NotificationFeignConfig.class)
public interface NotificationClient {
    @PostMapping(value = "/internal/notification-messages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Response send(@RequestHeader("X-INTERNAL-KEY") String key,
                  @RequestHeader("X-Correlation-ID") String correlationId,
                  @RequestBody String json);
}
