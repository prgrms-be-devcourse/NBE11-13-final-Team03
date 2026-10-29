package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.domain.CollectionSource;
import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.domain.repository.SyncHistoryRepository;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${app.scheduling.enabled:true} && ${collection.schedule.enabled:true}")
public class CollectionScheduler {
    private final ManualSyncService sync;
    private final SyncHistoryRepository histories;

    @Scheduled(cron = "0 45 9 * * *", zone = "Asia/Seoul")
    public void collectYouthPolicy() {
        start(CollectionSource.YOUTH_POLICY);
    }

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Seoul")
    public void collectEmployment24() {
        start(CollectionSource.EMPLOYMENT24);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void recoverMissedSchedule() {
        recoverMissedSchedule(ZonedDateTime.now(ZoneId.of("Asia/Seoul")));
    }

    void recoverMissedSchedule(ZonedDateTime now) {
        var from = now.toLocalDate().atStartOfDay(now.getZone()).toOffsetDateTime();
        var until = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).toOffsetDateTime();
        for (var source : CollectionSource.values()) {
            var scheduledTime = source == CollectionSource.YOUTH_POLICY ? LocalTime.of(9, 45) : LocalTime.of(9, 30);
            if (now.toLocalTime().isBefore(scheduledTime)) {
                continue;
            }
            try {
                if (!histories.existsBySourceSourceCodeAndStatusAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                        source.name(), SyncJobStatus.SUCCESS, from, until)
                        && !histories.existsBySourceSourceCodeAndSyncTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                        source.name(), "SCHEDULED", from, until)) {
                    start(source);
                }
            } catch (RuntimeException exception) {
                log.error("당일 수집 이력 확인 실패: source={}, error={}", source, exception.getClass().getSimpleName());
            }
        }
    }

    private void start(CollectionSource source) {
        try {
            sync.startScheduled(source);
        } catch (IllegalStateException exception) {
            log.info("정기 수집 건너뜀: source={} (비활성 또는 실행 중)", source);
        } catch (RuntimeException exception) {
            log.error("정기 수집 접수 실패: source={}, error={}", source, exception.getClass().getSimpleName());
        }
    }
}
