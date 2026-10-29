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
        var source = switch (result.dataSource()) {
            case "YOUTH_POLICY" -> "청년 정책";
            case "EMPLOYMENT24" -> "고용24";
            default -> throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_PAYLOAD");
        };
        var v2 = message.schemaVersion() == 2;
        var text = new StringBuilder("[수집 결과]")
                .append("\n수집일: ").append(result.collectedDate())
                .append("\n데이터 유형: ").append(source);
        if (v2) {
            var kind = switch (result.syncType()) {
                case "SCHEDULED" -> "정기 수집";
                case "MANUAL" -> "수동 수집";
                case "MANUAL_RETRY" -> "수동 재시도";
                default -> throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_PAYLOAD");
            };
            text.append(String.format(Locale.ROOT, "\n실행: #%d · %s", result.jobId(), kind));
        }
        text.append(String.format(Locale.ROOT, "\n신규: %,d건\n갱신: %,d건\n변경 없음: %,d건\n실패: %,d건",
                result.newCount(), result.updatedCount(), result.unchangedCount(), result.failedCount()));
        if (v2) {
            // 페이지 실패는 레코드 수로 환산하지 않는다. 레코드 실패가 0이어도 상태가 부분 성공인 이유를 보여 준다.
            var outcome = switch (result.status()) {
                case "SUCCESS" -> "성공";
                case "PARTIAL_SUCCESS" -> "부분 성공";
                case "FAILED" -> "실패";
                case "INTERRUPTED" -> "중단";
                default -> throw new IllegalArgumentException("INVALID_COLLECTION_COMPLETED_PAYLOAD");
            };
            text.append(String.format(Locale.ROOT, "\n페이지 실패: %,d건\n소요 시간: %d분 %02d초\n상태: %s",
                    result.failedPageCount(), result.durationMillis() / 60_000, result.durationMillis() / 1_000 % 60, outcome));
        }
        var request = HttpRequest.newBuilder(webhook).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("text", text.toString())), StandardCharsets.UTF_8))
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
