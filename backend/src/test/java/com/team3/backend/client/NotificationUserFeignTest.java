package com.team3.backend.client;

import com.team3.backend.client.config.NotificationFacadeProperties;
import com.team3.backend.client.dto.NotificationUserRequests;
import com.sun.net.httpserver.HttpServer;
import feign.Client;
import feign.Request;
import feign.Retryer;
import feign.http2client.Http2Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Real annotation-created Feign proxy and HTTP socket, including PATCH and error responses. */
@SpringBootTest(properties = {"NOTIFICATION_FACADE_ENABLED=true", "NOTIFICATION_PUBLISHER_ENABLED=false",
        "NOTIFICATION_INTERNAL_API_KEY=transport-test-key"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationUserFeignTest {
    private static HttpServer server;
    private static ExecutorService executor;
    private static final AtomicInteger status = new AtomicInteger(204);
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicReference<String> method = new AtomicReference<>();
    private static final AtomicReference<String> path = new AtomicReference<>();
    private static final AtomicReference<String> key = new AtomicReference<>();
    private static final AtomicReference<String> correlation = new AtomicReference<>();
    private static final AtomicReference<String> receivedBody = new AtomicReference<>();
    private static final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    @DynamicPropertySource
    static void peer(DynamicPropertyRegistry registry) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().toString());
            key.set(exchange.getRequestHeaders().getFirst("X-INTERNAL-KEY"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/must-not-follow");
                if (status.get() == 204) exchange.sendResponseHeaders(204, -1);
                else {
                    byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } finally { exchange.close(); }
        });
        server.start();
        registry.add("NOTIFICATION_BASE_URL", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stopPeer() { server.stop(0); executor.shutdownNow(); }

    @BeforeEach
    void reset() { calls.set(0); status.set(204); responseBody.set("{}"); }

    @Autowired NotificationUserClient client;
    @Autowired NotificationUserRelay relay;
    @Autowired FeignClientFactory factory;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper mapper;

    @Test
    void sendsRealPatchWithDedicatedKeyAndCorrelation() {
        try (var response = client.read(7, 9)) { assertThat(response.status()).isEqualTo(204); }
        assertThat(method.get()).isEqualTo("PATCH");
        assertThat(path.get()).isEqualTo("/internal/users/7/notifications/9/read");
        assertThat(key.get()).isEqualTo("transport-test-key");
        assertThatCode(() -> UUID.fromString(correlation.get())).doesNotThrowAnyException();
        try (var response = client.readAll(7)) { assertThat(response.status()).isEqualTo(204); }
        assertThat(method.get()).isEqualTo("PATCH");
        assertThat(path.get()).isEqualTo("/internal/users/7/notifications/read-all");
    }

    @Test
    void encodesSettingsAndEndpointAsJsonAndSendsCursor() {
        status.set(200);
        try (var response = client.settings(7, new NotificationUserRequests.Settings(true, false))) {
            assertThat(response.status()).isEqualTo(200);
        }
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(mapper.readTree(receivedBody.get()).path("pushEnabled").asBoolean()).isFalse();
        try (var response = client.endpoint(7, new NotificationUserRequests.Endpoint("test-token"))) {
            assertThat(response.status()).isEqualTo(200);
        }
        assertThat(method.get()).isEqualTo("POST");
        assertThat(mapper.readTree(receivedBody.get()).path("fcmToken").asText()).isEqualTo("test-token");
        try (var response = client.list(7, 123L, 10)) { assertThat(response.status()).isEqualTo(200); }
        assertThat(path.get()).contains("beforeId=123", "size=10");
    }

    @Test
    void noRedirectOrAutomaticRetryAndRaw404IsPreservedForRelay() {
        status.set(302);
        assertThat(relay.call(() -> client.read(7, 9), 204, false).getStatusCode().value()).isEqualTo(503);
        assertThat(calls).hasValue(1);
        calls.set(0);
        status.set(503);
        assertThat(relay.call(() -> client.read(7, 9), 204, false).getStatusCode().value()).isEqualTo(503);
        assertThat(calls).hasValue(1);
        status.set(404);
        assertThat(relay.call(() -> client.read(7, 9), 204, false, NotificationUserRelay.ContractError.NOT_FOUND)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void transportOptionsAndInterceptorAreConfinedToNewFeignChildContext() {
        assertThat(factory.getInstance("notification-user-api", Client.class)).isInstanceOf(Http2Client.class);
        var otherClient = factory.getInstance("notification-service", Client.class);
        assertThat(otherClient instanceof Http2Client).isFalse();
        assertThat(context.getBeansOfType(Http2Client.class)).isEmpty();
        var options = factory.getInstance("notification-user-api", Request.Options.class);
        assertThat(options.connectTimeoutMillis()).isEqualTo(1000);
        assertThat(options.readTimeoutMillis()).isEqualTo(2000);
        assertThat(options.isFollowRedirects()).isFalse();
        assertThat(factory.getInstance("notification-user-api", Retryer.class)).isSameAs(Retryer.NEVER_RETRY);
        assertThat(context.getBean(NotificationFacadeProperties.class).toString()).doesNotContain("transport-test-key");
    }
}
