package com.team3.backend.outbox;

import com.team3.backend.outbox.config.NotificationPublisherProperties;
import com.team3.backend.outbox.config.NotificationPublisherConfig;
import com.team3.backend.outbox.publisher.*;
import com.team3.backend.outbox.service.OutboxPublication;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HttpNotificationPublisherTest {
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> correlation = new AtomicReference<>();
    private volatile long delayMs;
    private String message;

    @BeforeEach
    void startHttpPeer() throws Exception {
        message = resource("notification-requested-v1.json");
        response.set(resource("notification-receipt-v1.json"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/internal/notification-messages", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("X-INTERNAL-KEY"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopPeer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void sendsCompleteJsonDedicatedKeyCorrelationAndChecksDurableReceipt() {
        withPublisher(publisher -> publisher.publish(publication()));
        assertThat(calls).hasValue(1);
        assertThat(body.get()).isEqualTo(message);
        assertThat(key.get()).isEqualTo("test-notification-key");
        assertThat(correlation.get()).isEqualTo("33333333-3333-4333-8333-333333333333");
    }

    @Test
    void withdrawalRequiresItsOwnDurableReceiptAndAcceptsWithdrawnSuppression() throws Exception {
        String control = resource("user-withdrawn-v1.json");
        var id = UUID.fromString("11111111-1111-4111-8111-111111111112");
        var controlPublication = new OutboxPublication(2L, id, UUID.randomUUID(), control);
        response.set("{\"messageId\":\"" + id + "\",\"status\":\"CREATED\",\"duplicateMessage\":false}");
        withPublisher(publisher -> {
            assertThatThrownBy(() -> publisher.publish(controlPublication)).isInstanceOf(NotificationPublicationException.class);
            response.set("{\"messageId\":\"" + id + "\",\"status\":\"WITHDRAWN\",\"duplicateMessage\":false}");
            assertThatCode(() -> publisher.publish(controlPublication)).doesNotThrowAnyException();
        });
        response.set(resource("notification-receipt-v1.json").replace("CREATED", "WITHDRAWN"));
        withPublisher(publisher -> assertThatCode(() -> publisher.publish(publication())).doesNotThrowAnyException());
    }

    @Test
    void rejectsEmptyWrongOrMalformedSuccessReceipts() {
        withPublisher(publisher -> {
            for (String invalid : new String[]{"", "{}", "<html>OK</html>",
                    response.get().replace("11111111-1111-4111-8111-111111111111", UUID.randomUUID().toString()),
                    response.get().replace("CREATED", "ACCEPTED")}) {
                response.set(invalid);
                assertThatThrownBy(() -> publisher.publish(publication()))
                        .isInstanceOfSatisfying(NotificationPublicationException.class, failure -> {
                            assertThat(failure.isRetryable()).isTrue();
                            assertThat(failure.getMessage()).isEqualTo("INVALID_RECEIPT");
                        });
            }
        });
    }

    @Test
    void classifiesHttpFailuresWithoutFeignRetriesOrCredentialLeakage() {
        withPublisher(publisher -> {
            for (int code : new int[]{400, 401, 403, 404, 409, 429, 500, 503, 302}) {
                status.set(code);
                int before = calls.get();
                // Wrong key, URL or route: a configuration error, not a per-message failure.
                boolean configuration = code == 401 || code == 403 || code == 404 || code == 302;
                assertThatThrownBy(() -> publisher.publish(publication()))
                        .isInstanceOfSatisfying(NotificationPublicationException.class, failure -> {
                            assertThat(failure.isConfiguration()).isEqualTo(configuration);
                            assertThat(failure.isRetryable()).isEqualTo(configuration || code == 429 || code >= 500);
                            assertThat(failure.getMessage()).isEqualTo("HTTP_" + code)
                                    .doesNotContain("test-notification-key");
                            assertThat(failure.getCause()).isNull();
                        });
                assertThat(calls.get()).isEqualTo(before + 1);
            }
        });
    }

    @Test
    void readTimeoutIsRetryableAndDoesNotRetryWithinFeign() {
        delayMs = 1000;
        withPublisher(publisher -> {
            assertThatThrownBy(() -> publisher.publish(publication()))
                    .isInstanceOfSatisfying(NotificationPublicationException.class,
                            failure -> assertThat(failure.isRetryable()).isTrue());
            assertThat(calls).hasValue(1);
        });
    }

    private void withPublisher(Consumer<NotificationMessagePublisher> assertions) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class))
                .withUserConfiguration(NotificationPublisherConfig.class, NotificationFeignTestConfiguration.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withPropertyValues("NOTIFICATION_PUBLISHER_ENABLED=true",
                        "NOTIFICATION_INTERNAL_API_KEY=test-notification-key",
                        "NOTIFICATION_BASE_URL=http://127.0.0.1:" + server.getAddress().getPort(),
                        "yon.notification.publisher.connect-timeout=1s",
                        "yon.notification.publisher.read-timeout=200ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertions.accept(context.getBean(NotificationMessagePublisher.class));
                });
    }

    private OutboxPublication publication() {
        return new OutboxPublication(1L, UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.randomUUID(), message);
    }

    static NotificationPublisherProperties properties(int port) {
        return new NotificationPublisherProperties(true, "http", "http://127.0.0.1:" + port,
                "test-notification-key", 10, 3, Duration.ofMinutes(1), Duration.ofMinutes(1),
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private String resource(String name) throws Exception {
        return new ClassPathResource("contracts/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
