package com.team3.backend.tracking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${app.scheduling.enabled:true} && ${yon.tracking.fanout.enabled:false}")
public class EventFanoutScheduler {
    private final EventFanoutWorker worker;

    @Scheduled(fixedDelayString = "${yon.tracking.fanout.poll-interval-ms:60000}")
    public void run() {
        try {
            worker.runOnce();
        } catch (RuntimeException failure) {
            log.error("Event fanout polling failed: {}", failure.getClass().getSimpleName());
        }
    }
}
