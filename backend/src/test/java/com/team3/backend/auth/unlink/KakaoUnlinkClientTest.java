package com.team3.backend.auth.unlink;

import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KakaoUnlinkClientTest {
    private final HttpClient http = mock(HttpClient.class);
    private final KakaoUnlinkProperties properties = new KakaoUnlinkProperties(true,"server-secret",3,
            Duration.ofMinutes(1),Duration.ofSeconds(5),1);
    private final KakaoUnlinkClient client = new KakaoUnlinkClient(http, properties, new ObjectMapper());

    @SuppressWarnings("unchecked")
    private void response(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    @Test void sendsServerOnlyFormAndRequiresMatchingReceipt() throws Exception {
        response(200,"{\"id\":123}");
        client.unlink(123);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://kapi.kakao.com/v1/user/unlink");
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("KakaoAK server-secret");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(5));
    }
    @Test void permanentErrorDoesNotLeakBodyOrKey() throws Exception {
        response(401,"server-secret private token");
        assertThatThrownBy(() -> client.unlink(123)).isInstanceOfSatisfying(KakaoUnlinkClient.Failure.class, e -> {
            assertThat(e.retryable()).isFalse(); assertThat(e.getMessage()).isEqualTo("KAKAO_HTTP_401");
            assertThat(e.getCause()).isNull();
        });
    }
    @Test void serverFailureIsRetryable() throws Exception {
        response(503,"private-response");
        assertThatThrownBy(() -> client.unlink(123)).isInstanceOfSatisfying(KakaoUnlinkClient.Failure.class,
                e -> assertThat(e.retryable()).isTrue());
    }
    @Test void wrongReceiptCannotReleaseRejoinGate() throws Exception {
        response(200,"{\"id\":999}");
        assertThatThrownBy(() -> client.unlink(123)).hasMessage("KAKAO_INVALID_RECEIPT");
    }
    @Test void networkTimeoutIsSanitized() throws Exception {
        when(http.send(any(),any())).thenThrow(new IOException("private-secret"));
        assertThatThrownBy(() -> client.unlink(123)).isInstanceOfSatisfying(KakaoUnlinkClient.Failure.class, e -> {
            assertThat(e.getMessage()).isEqualTo("KAKAO_OUTCOME_UNKNOWN");
            assertThat(e.retryable()).isFalse(); assertThat(e.getCause()).isNull();
        });
    }
    @Test void enabledRequiresKeyAndPropertiesNeverPrintKey() {
        assertThat(properties.toString()).doesNotContain("server-secret");
        assertThatThrownBy(() -> new KakaoUnlinkProperties(true,"",3,Duration.ofMinutes(1),Duration.ofSeconds(5),1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
