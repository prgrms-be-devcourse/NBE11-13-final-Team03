package com.team3.backend.client;

import com.team3.backend.client.config.NotificationFacadeProperties;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class NotificationUserRelayTest {
    @Test
    void disabledFacadeDoesNotCallPeerAndCountIsExplicitlyUnavailable() {
        var calls = new AtomicInteger();
        var relay = relay(false);
        assertThat(relay.call(() -> { calls.incrementAndGet(); return response(200, "{}"); }, 200, false)
                .getStatusCode().value()).isEqualTo(503);
        var count = relay.call(() -> response(200, "{}"), 200, true);
        assertThat(count.getBody()).isEqualTo(new NotificationUserRelay.UnreadUnavailable(false, null));
        assertThat(calls).hasValue(0);
    }

    @Test
    void preservesContractErrorsButRejectsRedirectAuthenticationAndMalformedResponses() {
        var relay = relay(true);
        for (int status : new int[]{400, 404, 409}) {
            assertThat(relay.call(() -> response(status, "secret"), 200, false).getStatusCode().value()).isEqualTo(status);
        }
        for (int status : new int[]{201, 302, 401, 403, 429, 500, 503}) {
            assertThat(relay.call(() -> response(status, "secret"), 200, false).getStatusCode().value()).isEqualTo(503);
        }
        for (String body : new String[]{"", "[]", "not-json", "{\"data\":\"" + "x".repeat(1024 * 1024) + "\"}"}) {
            assertThat(relay.call(() -> response(200, body), 200, false).getStatusCode().value()).isEqualTo(503);
        }
        assertThat(relay.call(() -> response(200, "{\"available\":true,\"unreadCount\":0}"), 200, true)
                .getStatusCode().value()).isEqualTo(200);
        for (String count : new String[]{"-1", "null", "1.5", "9223372036854775808"}) {
            assertThat(relay.call(() -> response(200, "{\"available\":true,\"unreadCount\":" + count + "}"), 200, true)
                    .getBody()).isEqualTo(new NotificationUserRelay.UnreadUnavailable(false, null));
        }
    }

    @Test
    void saturatedPeerCallsFailFastAndReleaseSlotAfterFailure() throws Exception {
        var relay = relay(true);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> relay.call(() -> {
                entered.countDown();
                try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                throw new IllegalStateException("down");
            }, 200, false));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(relay.call(() -> { throw new AssertionError("Must fail before HTTP call"); }, 200, false)
                        .getStatusCode().value()).isEqualTo(503);
            } finally { finish.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(503);
        }
        assertThat(relay.call(() -> response(200, "{}"), 200, false).getStatusCode().value()).isEqualTo(200);
    }

    private NotificationUserRelay relay(boolean enabled) {
        return new NotificationUserRelay(new NotificationFacadeProperties(enabled, "http://127.0.0.1:1", "key", 1),
                JsonMapper.builder().build());
    }
    private Response response(int status, String body) {
        return Response.builder().status(status).reason("test").headers(Map.of())
                .request(Request.create(Request.HttpMethod.GET, "http://test", Map.of(), null, StandardCharsets.UTF_8, null))
                .body(body, StandardCharsets.UTF_8).build();
    }
}
