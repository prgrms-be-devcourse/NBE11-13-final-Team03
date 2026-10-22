package com.team3.backend.opportunity.ingest;

import com.team3.backend.global.role.ApiRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기 실행. NOTIFY 누락 대비 정제 요청 폴링(짧은 주기)과 원본·정규화 대조(긴 주기). 기본값은 1분 / 1시간.
 * 끄려면 yon.normalization.ingest.enabled=false (LISTEN도 같이 꺼진다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ApiRole
@ConditionalOnProperty(prefix = "yon.normalization.ingest", name = "enabled", havingValue = "true")
public class RawIngestScheduler {

    private final RawIngestProcessor processor;

    @Scheduled(initialDelayString = "${yon.normalization.ingest.poll-initial-delay-ms:30000}",
            fixedDelayString = "${yon.normalization.ingest.poll-interval-ms:60000}")
    public void pollQueue() {
        try {
            processor.processQueue();
        } catch (RuntimeException e) {
            log.warn("Normalization request polling failed", e);
        }
    }

    @Scheduled(initialDelayString = "${yon.normalization.ingest.reconcile-initial-delay-ms:300000}",
            fixedDelayString = "${yon.normalization.ingest.reconcile-interval-ms:3600000}")
    public void reconcile() {
        try {
            processor.reconcile();
        } catch (RuntimeException e) {
            log.warn("Raw record reconciliation failed", e);
        }
    }
}
