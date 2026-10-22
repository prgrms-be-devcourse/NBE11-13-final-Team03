package com.team3.backend.opportunity.ingest;

import com.team3.backend.global.role.ApiRole;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * 수집 영역의 NOTIFY(채널 기본값 raw_normalization_requested, payload는 정제 요청 등의 고유 ID)를 받아 정제 요청을 바로 처리한다.
 * 알림은 "깨워라" 신호로만 쓰고 내용은 보지 않는다. 놓쳐도 주기적 폴링·대조(RawIngestScheduler)가 처리하므로 데이터는 빠지지 않는다.
 *
 * LISTEN은 연결이 살아 있는 동안만 유효해 Hikari 풀과 별도로 전용 연결 1개를 둔다. 끊기면 잠시 뒤 다시 연결하고,
 * 다시 연결할 때마다 쌓인 요청부터 처리한다. 처리 중 도착한 알림은 연결에 쌓였다가 다음 대기 때 한꺼번에 읽힌다 (처리 1번으로 합쳐짐).
 */
@Slf4j
@Component
@ApiRole
@ConditionalOnProperty(prefix = "yon.normalization.ingest", name = "enabled", havingValue = "true")
public class RawIngestListener implements SmartLifecycle {

    private static final Pattern CHANNEL = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final long RECONNECT_DELAY_MS = 5_000;

    private final RawIngestProcessor processor;
    private final String url;
    private final String username;
    private final String password;
    private final String channel;
    private final int waitMs;

    private volatile boolean running;
    private Thread thread;

    public RawIngestListener(RawIngestProcessor processor,
                             @Value("${spring.datasource.url}") String url,
                             @Value("${spring.datasource.username:}") String username,
                             @Value("${spring.datasource.password:}") String password,
                             @Value("${yon.normalization.ingest.channel:raw_normalization_requested}") String channel,
                             @Value("${yon.normalization.ingest.listen-wait-ms:30000}") int waitMs) {
        if (!CHANNEL.matcher(channel).matches()) {
            throw new IllegalArgumentException("NOTIFY 채널 이름은 소문자·숫자·밑줄만 쓸 수 있습니다: " + channel);
        }
        this.processor = processor;
        this.url = url;
        this.username = username;
        this.password = password;
        this.channel = channel;
        this.waitMs = waitMs;
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::loop, "yon-raw-ingest-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                PGConnection pg = connection.unwrap(PGConnection.class);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + channel);
                }
                log.info("Listening for normalization requests: channel={}", channel);
                processQuietly();
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(waitMs);
                    if (notifications != null && notifications.length > 0) {
                        processQuietly();
                    }
                }
            } catch (SQLException e) {
                if (running) {
                    log.warn("Normalization listener disconnected, retrying in {}ms: {}", RECONNECT_DELAY_MS, e.getMessage());
                    sleep();
                }
            }
        }
    }

    private void processQuietly() {
        try {
            processor.processQueue();
        } catch (RuntimeException e) {
            log.warn("Normalization request processing failed", e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
