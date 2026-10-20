package com.team3.backend.tracking.service;

import com.team3.backend.tracking.domain.entity.EventScanHistory;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.model.EventScanExecution;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
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
@Import(EventScanCoordinator.class)
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

    @BeforeEach
    void setUp() {
        historyRepository.deleteAll();
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
                        start.await();

                        return coordinator.tryStart(
                                SCAN_DATE,
                                NOW
                        );
                    });

            Future<Optional<EventScanExecution>> second =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();

                        return coordinator.tryStart(
                                SCAN_DATE,
                                NOW
                        );
                    });

            ready.await();
            start.countDown();

            List<Optional<EventScanExecution>> results =
                    List.of(
                            first.get(),
                            second.get()
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