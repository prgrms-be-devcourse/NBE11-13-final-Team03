package com.team3.notification.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** One bounded attempt; queue redelivery retries failures. Never log the secret webhook or remote body. */
public class SlackWebhookSender implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI webhook;
    private final ObjectMapper mapper;

    public SlackWebhookSender(URI webhook, ObjectMapper mapper) {
        this.webhook = webhook;
        this.mapper = mapper;
    }

    public void send(CollectionCompletedMessage message) {
        var result = message.payload();
        var text = String.format(Locale.ROOT,
                "[수집 완료] %s %s · 새 %,d · 교체 %,d · 유지 %,d · 실패 %,d",
                result.dataSource(), result.collectedDate(), result.newCount(), result.updatedCount(),
                result.unchangedCount(), result.failedCount());
        if (message.schemaVersion() == 2) {
            var type = switch (result.syncType()) {
                case "SCHEDULED" -> "정기 수집";
                case "MANUAL" -> "수동 수집";
                default -> "수동 재시도";
            };
            var outcome = switch (result.status()) {
                case "SUCCESS" -> "성공";
                case "PARTIAL_SUCCESS" -> "부분 실패";
                case "FAILED" -> "실패";
                default -> "중단";
            };
            text = String.format(Locale.ROOT,
                    "[%s · %s] %s %s · 실행 #%d · 새 %,d · 교체 %,d · 유지 %,d · 레코드 실패 %,d · 페이지 실패 %,d · 소요 %d분 %02d.%03d초",
                    type, outcome, result.dataSource(), result.collectedDate(), result.jobId(), result.newCount(),
                    result.updatedCount(), result.unchangedCount(), result.failedCount(), result.failedPageCount(),
                    result.durationMillis() / 60_000, result.durationMillis() / 1_000 % 60, result.durationMillis() % 1_000);
        }
        var request = HttpRequest.newBuilder(webhook).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("text", text)), StandardCharsets.UTF_8))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || !"ok".equals(response.body().trim())) {
                throw new IllegalStateException("ADMIN_SLACK_DELIVERY_FAILED");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ADMIN_SLACK_DELIVERY_INTERRUPTED");
        } catch (IOException failure) {
            throw new IllegalStateException("ADMIN_SLACK_DELIVERY_FAILED");
        }
    }

    @Override public void close() { http.close(); }
}
