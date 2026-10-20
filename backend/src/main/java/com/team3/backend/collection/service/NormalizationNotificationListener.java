package com.team3.backend.collection.service;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 알림은 깨우기 신호이며 실제 처리 대상은 항상 영속 요청 큐에서 조회한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "collection.normalization.enabled", havingValue = "true", matchIfMissing = true)
public class NormalizationNotificationListener {
    static final String CHANNEL = "raw_normalization_requested";
    private static final Logger log = LoggerFactory.getLogger(NormalizationNotificationListener.class);
    private final DataSource dataSource;
    private final ApplicationEventPublisher events;
    private volatile boolean running;
    private volatile Connection connection;
    private Thread worker;

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws SQLException {
        try (var candidate = dataSource.getConnection()) {
            if (!candidate.isWrapperFor(PGConnection.class)) {
                return;
            }
            installTrigger(candidate);
        }
        running = true;
        worker = Thread.ofVirtual().name("normalization-listener").start(this::listen);
    }

    static void installTrigger(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    create or replace function notify_raw_normalization_request() returns trigger
                    language plpgsql as $$
                    begin
                        perform pg_notify('raw_normalization_requested', NEW.id::text);
                        return NEW;
                    end;
                    $$
                    """);
            statement.execute("""
                    create or replace trigger raw_normalization_request_notify
                    after insert on raw_normalization_request
                    for each row execute function notify_raw_normalization_request()
                    """);
        }
    }

    private void listen() {
        while (running) {
            try (var listening = dataSource.getConnection()) {
                connection = listening;
                listening.setAutoCommit(true);
                try (var statement = listening.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                var postgres = listening.unwrap(PGConnection.class);
                // LISTEN 커밋 이후 조회하여 시작·재연결 사이에 놓친 요청도 처리한다.
                try {
                    while (running) {
                        signalPendingRequests();
                        postgres.getNotifications(30_000);
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
            } finally {
                connection = null;
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
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        var listening = connection;
        if (listening != null) {
            try {
                listening.close();
            } catch (SQLException exception) {
                log.warn("정제 알림 연결 종료 실패", exception);
            }
        }
    }
}
