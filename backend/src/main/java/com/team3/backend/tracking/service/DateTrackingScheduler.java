package com.team3.backend.tracking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${app.scheduling.enabled:true} && ${yon.tracking.date.enabled:false}")
public class DateTrackingScheduler {
    private final DateTrackingService service;

    @Scheduled(fixedDelayString = "${yon.tracking.date.poll-interval-ms:60000}")
    public void scan() {
        try {
            service.runDueScans();
        } catch (RuntimeException exception) {
            log.error("Date tracking scan failed: {}", exception.getClass().getSimpleName());
        }
    }
}
