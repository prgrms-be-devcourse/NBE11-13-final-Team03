package com.team3.backend.auth.unlink;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import tools.jackson.databind.ObjectMapper;

public class KakaoUnlinkClient {
    private final HttpClient client;
    private final KakaoUnlinkProperties properties;
    private final ObjectMapper mapper;
    public KakaoUnlinkClient(HttpClient client, KakaoUnlinkProperties properties, ObjectMapper mapper) {
        this.client = client; this.properties = properties; this.mapper = mapper;
    }
    public record Receipt(Long id) { }
    public static class Failure extends RuntimeException {
        private final boolean retryable;
        public Failure(String safeCode, boolean retryable) { super(safeCode); this.retryable = retryable; }
        public boolean retryable() { return retryable; }
    }
    public void unlink(long kakaoId) {
        var request = HttpRequest.newBuilder(URI.create("https://kapi.kakao.com/v1/user/unlink"))
                .timeout(properties.timeout()).header("Authorization", "KakaoAK " + properties.adminKey())
                .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("target_id_type=user_id&target_id=" + kakaoId)).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 200) throw new Failure("KAKAO_HTTP_" + status, status == 429 || status >= 500);
            if (!Long.valueOf(kakaoId).equals(mapper.readValue(response.body(), Receipt.class).id())) {
                throw new Failure("KAKAO_INVALID_RECEIPT", false);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Failure("KAKAO_OUTCOME_UNKNOWN", false);
        } catch (IOException failure) {
            throw new Failure("KAKAO_OUTCOME_UNKNOWN", false);
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new Failure("KAKAO_INVALID_RESPONSE", false);
        }
    }
}
