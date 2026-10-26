package com.team3.notification.admin;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SlackWebhookSenderTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void postsOnlyTheSixResultFieldsAndRequiresSlackOk() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook-secret", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8)); exchange.close();
        });
        server.start();
        try (var sender = new SlackWebhookSender(URI.create(url(server, "/webhook-secret")), mapper)) {
            sender.send(CollectionCompletedMessage.decode(mapper, CollectionCompletedMessageTest.fixture()));
            var body = mapper.readTree(received.get());
            assertThat(body.size()).isEqualTo(1);
            assertThat(body.path("text").asString()).isEqualTo(
                    "[수집 완료] EMPLOYMENT24 2026-10-27 · 새 1,203 · 교체 88 · 유지 139,201 · 실패 4");
        } finally { server.stop(0); }
    }

    @Test
    void displaysV2ExecutionKindFinalOutcomeAndElapsedTime() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook-secret", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8)); exchange.close();
        });
        server.start();
        try (var sender = new SlackWebhookSender(URI.create(url(server, "/webhook-secret")), mapper)) {
            var fixture = CollectionCompletedMessageTest.v2Fixture();
            sender.send(CollectionCompletedMessage.decode(mapper, fixture));
            assertThat(mapper.readTree(received.get()).path("text").asString()).isEqualTo(
                    "[수동 재시도 · 부분 실패] EMPLOYMENT24 2026-10-27 · 실행 #7 · 새 1,203 · 교체 88 · 유지 139,201 · 레코드 실패 4 · 페이지 실패 1 · 소요 7분 00.000초");
            var successful = fixture.replace("PARTIAL_SUCCESS", "SUCCESS").replace("\"failedCount\": 4", "\"failedCount\": 0")
                    .replace("\"failedPageCount\": 1", "\"failedPageCount\": 0");
            sender.send(CollectionCompletedMessage.decode(mapper, successful));
            assertThat(received.get()).contains("수동 재시도 · 성공");
            sender.send(CollectionCompletedMessage.decode(mapper, fixture.replace("MANUAL_RETRY", "SCHEDULED")
                    .replace("PARTIAL_SUCCESS", "FAILED")));
            assertThat(received.get()).contains("정기 수집 · 실패");
            sender.send(CollectionCompletedMessage.decode(mapper, fixture.replace("MANUAL_RETRY", "MANUAL")
                    .replace("PARTIAL_SUCCESS", "INTERRUPTED")));
            assertThat(received.get()).contains("수동 수집 · 중단");
        } finally { server.stop(0); }
    }

    @Test
    void failuresAndRedirectsNeverExposeOrForwardTheWebhookSecret() throws Exception {
        var forwarded = new AtomicInteger();
        var status = new AtomicInteger(500);
        var reply = new AtomicReference<>("private-token");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook-secret", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", url(server, "/other"));
            var bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/other", exchange -> { forwarded.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try (var sender = new SlackWebhookSender(URI.create(url(server, "/webhook-secret")), mapper)) {
            var message = CollectionCompletedMessage.decode(mapper, CollectionCompletedMessageTest.fixture());
            for (int code : new int[]{500, 429, 302, 200}) {
                status.set(code);
                assertThatThrownBy(() -> sender.send(message)).isInstanceOf(IllegalStateException.class)
                        .hasMessage("ADMIN_SLACK_DELIVERY_FAILED").hasNoCause();
            }
            assertThat(forwarded.get()).isZero();
        } finally { server.stop(0); }
    }

    private static String url(HttpServer server, String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
}
