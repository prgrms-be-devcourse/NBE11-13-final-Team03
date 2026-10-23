package com.team3.backend.tracking.service;

import com.team3.backend.tracking.domain.entity.EventScanHistory;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.model.EventScanExecution;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.tracking.config.DateTrackingProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
@ActiveProfiles("test")
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({EventScanCoordinator.class, DateTrackingBatch.class, ApplicationStatusCalculator.class,
        ApplicationStatusTransitionDetector.class, DeadlineSoonDetector.class, OpportunityEventFactory.class,
        EventScanCoordinatorIntegrationTest.ScanConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventScanCoordinatorIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("event_scan_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );

        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );

        registry.add(
                "spring.datasource.driver-class-name",
                postgres::getDriverClassName
        );
    }

    private static final LocalDate SCAN_DATE =
            LocalDate.of(2026, 10, 20);

    private static final Instant NOW =
            Instant.parse("2026-10-20T02:00:00Z");

    @Autowired
    private EventScanCoordinator coordinator;

    @Autowired
    private EventScanHistoryRepository historyRepository;

    @Autowired private DateTrackingBatch batch;
    @Autowired private OpportunityEventRepository events;
    @Autowired private OpportunityRepository opportunities;
    @Autowired private OpportunityVersionRepository versions;
    @Autowired private ApplicationWindowRepository windows;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        historyRepository.deleteAll();
        new JdbcTemplate(dataSource).update("UPDATE opportunities SET current_version_id = NULL");
        windows.deleteAll();
        versions.deleteAll();
        opportunities.deleteAll();
    }

    @Test
    void expiredRunningScanIsFencedAndCannotWriteOrChangeTheNewOwner() {
        var old = coordinator.tryStart(SCAN_DATE, NOW, Duration.ofMinutes(30)).orElseThrow();
        assertThat(coordinator.tryStart(SCAN_DATE, NOW.plusSeconds(60), Duration.ofMinutes(30))).isEmpty();
        var current = coordinator.tryStart(SCAN_DATE, NOW.plusSeconds(1800), Duration.ofMinutes(30)).orElseThrow();
        assertThat(current.executionId()).isNotEqualTo(old.executionId());
        assertThat(coordinator.complete(old, NOW.plusSeconds(1801))).isFalse();
        assertThat(coordinator.fail(old, NOW.plusSeconds(1801), "stale")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> batch.scan(old, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ownership lost");
        assertThat(historyRepository.findByScanDate(SCAN_DATE).orElseThrow().getExecutionId())
                .isEqualTo(current.executionId());
    }

    @Test
    void takeoverWaitsForPageCommitAndHonorsTheCommittedHeartbeat() throws Exception {
        coordinator.tryStart(SCAN_DATE, NOW).orElseThrow();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var page = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> {
                            var history = historyRepository.lockByScanDate(SCAN_DATE).orElseThrow();
                            locked.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Timed out waiting for test release");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            history.heartbeat(NOW.plusSeconds(1801));
                        }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var claim = executor.submit(() ->
                        coordinator.tryStart(SCAN_DATE, NOW.plusSeconds(1801), Duration.ofMinutes(30)));
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> claim.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                page.get(10, TimeUnit.SECONDS);
                assertThat(claim.get(10, TimeUnit.SECONDS)).isEmpty();
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void pageCommitsSurviveFailureAndRestartDoesNotDuplicateEvents() {
        policy(OpportunityType.YOUTH_POLICY, SCAN_DATE.plusDays(3), ValidationStatus.VALID);
        var first = coordinator.tryStart(SCAN_DATE, NOW).orElseThrow();
        var page = batch.scan(first, 0);
        assertThat(page.complete()).isFalse();
        assertThat(events.count()).isEqualTo(1);
        var event = events.findAll().getFirst();
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getCorrelationId().toString()).isEqualTo(first.executionId());
        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-10-19T15:00:00Z"));
        assertThat(event.getNextRetryAt()).isEqualTo(NOW);
        coordinator.fail(first, NOW.plusSeconds(1), "interrupted after page commit");
        var restarted = coordinator.tryStart(SCAN_DATE, NOW.plusSeconds(2)).orElseThrow();
        var replay = batch.scan(restarted, 0);
        assertThat(batch.scan(restarted, replay.afterId()).complete()).isTrue();
        assertThat(events.count()).isEqualTo(1);
        assertThat(events.findAll().getFirst().getEventId()).isEqualTo(event.getEventId());
        assertThat(historyRepository.findByScanDate(SCAN_DATE).orElseThrow().getStatus())
                .isEqualTo(EventScanStatus.SUCCESS);
    }

    @Test
    void historicalDeadlineIsRecordedButSuppressed() {
        policy(OpportunityType.YOUTH_POLICY, SCAN_DATE.plusDays(5), ValidationStatus.VALID);
        var execution = coordinator.tryStart(SCAN_DATE.minusDays(2), NOW).orElseThrow();
        batch.scan(execution, 0);
        var event = events.findAll().getFirst();
        assertThat(event.isNotificationEligible()).isFalse();
        assertThat(event.getSuppressionReason()).isEqualTo(
                com.team3.backend.tracking.domain.entity.OpportunityEventSuppressionReason.THRESHOLD_ALREADY_PASSED);
        assertThat(event.getPayload()).containsEntry("applicationEndDate", SCAN_DATE.plusDays(5).toString());
    }

    @Test
    void usesOnlyYouthPoliciesWithCurrentValidApplicationWindows() {
        policy(OpportunityType.VOCATIONAL_TRAINING, SCAN_DATE.plusDays(3), ValidationStatus.VALID);
        policy(OpportunityType.YOUTH_POLICY, SCAN_DATE.plusDays(3), ValidationStatus.INVALID);
        policy(OpportunityType.YOUTH_POLICY, SCAN_DATE.plusDays(9), ValidationStatus.VALID);
        var execution = coordinator.tryStart(SCAN_DATE, NOW).orElseThrow();
        long after = 0;
        while (true) {
            var result = batch.scan(execution, after);
            if (result.complete()) break;
            after = result.afterId();
        }
        assertThat(events.count()).isZero();
    }

    private void policy(OpportunityType type, LocalDate endDate, ValidationStatus validation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var opportunity = opportunities.saveAndFlush(Opportunity.builder().title("policy")
                    .opportunityType(type).build());
            var old = versions.saveAndFlush(OpportunityVersion.builder().opportunity(opportunity).versionNo(1)
                    .normalizedSnapshot(Map.of()).normalizationVersion("test")
                    .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build());
            windows.saveAndFlush(ApplicationWindow.builder().version(old).periodType(PeriodType.FIXED)
                    .startDate(SCAN_DATE.minusDays(20)).endDate(SCAN_DATE.plusDays(3))
                    .validationStatus(ValidationStatus.VALID).build());
            var current = versions.saveAndFlush(OpportunityVersion.builder().opportunity(opportunity).versionNo(2)
                    .normalizedSnapshot(Map.of()).normalizationVersion("test")
                    .personalizationStatus(PersonalizationStatus.PERSONALIZABLE).build());
            opportunity.changeCurrentVersion(current);
            windows.saveAndFlush(ApplicationWindow.builder().version(current).periodType(PeriodType.FIXED)
                    .startDate(SCAN_DATE.minusDays(20)).endDate(endDate).validationStatus(validation).build());
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScanConfiguration {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean DateTrackingProperties properties() {
            return new DateTrackingProperties(false, LocalTime.of(13, 0), Duration.ofMinutes(30), 1, 7, null, 3);
        }
    }

    @Test
    void 동일한_날짜의_최초_실행권은_한_번만_획득한다() {
        Optional<EventScanExecution> first =
                coordinator.tryStart(SCAN_DATE, NOW);

        Optional<EventScanExecution> second =
                coordinator.tryStart(
                        SCAN_DATE,
                        NOW.plusSeconds(1)
                );

        assertThat(first).isPresent();
        assertThat(second).isEmpty();

        List<EventScanHistory> histories =
                historyRepository.findAll();

        assertThat(histories).hasSize(1);
        assertThat(histories.getFirst().getStatus())
                .isEqualTo(EventScanStatus.RUNNING);
    }

    @Test
    void 실패한_날짜는_새로운_실행_식별자로_재시작한다() {
        EventScanExecution first =
                coordinator.tryStart(SCAN_DATE, NOW)
                        .orElseThrow();

        boolean failed = coordinator.fail(
                first,
                NOW.plusSeconds(10),
                "정책 조회 실패"
        );

        EventScanExecution restarted =
                coordinator.tryStart(
                                SCAN_DATE,
                                NOW.plusSeconds(20)
                        )
                        .orElseThrow();

        EventScanHistory history =
                historyRepository.findByScanDate(SCAN_DATE)
                        .orElseThrow();

        assertThat(failed).isTrue();
        assertThat(restarted.executionId())
                .isNotEqualTo(first.executionId());

        assertThat(history.getExecutionId())
                .isEqualTo(restarted.executionId());
        assertThat(history.getStatus())
                .isEqualTo(EventScanStatus.RUNNING);
        assertThat(history.getRetryCount()).isEqualTo(1);
    }

    @Test
    void 재시작된_후에는_이전_실행이_완료_처리할_수_없다() {
        EventScanExecution first =
                coordinator.tryStart(SCAN_DATE, NOW)
                        .orElseThrow();

        coordinator.fail(
                first,
                NOW.plusSeconds(10),
                "일시적인 오류"
        );

        EventScanExecution restarted =
                coordinator.tryStart(
                                SCAN_DATE,
                                NOW.plusSeconds(20)
                        )
                        .orElseThrow();

        boolean oldExecutionCompleted =
                coordinator.complete(
                        first,
                        NOW.plusSeconds(30)
                );

        boolean currentExecutionCompleted =
                coordinator.complete(
                        restarted,
                        NOW.plusSeconds(31)
                );

        EventScanHistory history =
                historyRepository.findByScanDate(SCAN_DATE)
                        .orElseThrow();

        assertThat(oldExecutionCompleted).isFalse();
        assertThat(currentExecutionCompleted).isTrue();
        assertThat(history.getStatus())
                .isEqualTo(EventScanStatus.SUCCESS);
    }

    @Test
    void 동일한_날짜를_동시에_점유하면_한_작업만_성공한다()
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<Optional<EventScanExecution>> first =
                    executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");

                        return coordinator.tryStart(
                                SCAN_DATE,
                                NOW
                        );
                    });

            Future<Optional<EventScanExecution>> second =
                    executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");

                        return coordinator.tryStart(
                                SCAN_DATE,
                                NOW
                        );
                    });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<EventScanExecution>> results =
                    List.of(
                            first.get(10, TimeUnit.SECONDS),
                            second.get(10, TimeUnit.SECONDS)
                    );

            long successCount = results.stream()
                    .filter(Optional::isPresent)
                    .count();

            assertThat(successCount).isEqualTo(1);
            assertThat(historyRepository.findAll())
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
