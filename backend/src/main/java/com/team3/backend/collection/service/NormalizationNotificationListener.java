package com.team3.backend.collection.service;

import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.postgresql.PGConnection;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 알림은 깨우기 신호이며 실제 처리 대상은 항상 영속 요청 큐에서 조회한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${collection.normalization.enabled:false} && "
        + "('${app.role:all}' == 'api' || '${app.role:all}' == 'all')")
public class NormalizationNotificationListener {
    static final String CHANNEL = "raw_normalization_requested";
    private static final Logger log = LoggerFactory.getLogger(NormalizationNotificationListener.class);
    private final DataSourceProperties dataSourceProperties;
    private final ApplicationEventPublisher events;
    private volatile boolean running;
    private Thread worker;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if ((worker != null && worker.isAlive())
                || !dataSourceProperties.determineUrl().startsWith("jdbc:postgresql:")) {
            return;
        }
        var notifications = notificationDataSource();
        running = true;
        worker = Thread.ofVirtual().name("normalization-listener").start(() -> listen(notifications));
    }

    DataSource notificationDataSource() {
        var notifications = new PGSimpleDataSource();
        notifications.setUrl(dataSourceProperties.determineUrl());
        notifications.setUser(dataSourceProperties.determineUsername());
        notifications.setPassword(dataSourceProperties.determinePassword());
        return notifications;
    }

    private void listen(DataSource notifications) {
        while (running) {
            // PGSimpleDataSource는 풀을 사용하지 않으며 close()가 실제 세션을 종료한다.
            try (var listening = notifications.getConnection()) {
                listening.setAutoCommit(true);
                try (var statement = listening.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                var postgres = listening.unwrap(PGConnection.class);
                try {
                    signalPendingRequests();
                    long nextSignal = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    while (running) {
                        // 짧은 대기로 종료 요청을 확인하되 PENDING 조회 신호는 30초마다 보낸다.
                        var received = postgres.getNotifications(1_000);
                        if (running && ((received != null && received.length > 0)
                                || System.nanoTime() - nextSignal >= 0)) {
                            signalPendingRequests();
                            nextSignal = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                        }
                    }
                } finally {
                    if (!listening.isClosed()) {
                        try (var statement = listening.createStatement()) {
                            statement.execute("UNLISTEN " + CHANNEL);
                        }
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                if (running) {
                    log.warn("정제 알림 연결 실패. 재연결 후 대기 요청을 조회합니다.", exception);
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    void signalPendingRequests() {
        try {
            events.publishEvent(new NormalizationRequestsAvailable());
        } catch (RuntimeException exception) {
            log.error("정제 요청 수신 이벤트 전달 실패. 다음 주기에 다시 전달합니다.", exception);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (worker.isAlive()) {
                    worker.interrupt();
                }
            }
        }
    }
}
