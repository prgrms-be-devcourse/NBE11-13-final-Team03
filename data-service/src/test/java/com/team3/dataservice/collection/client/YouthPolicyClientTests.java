package com.team3.dataservice.collection.client;

import com.team3.dataservice.collection.dto.external.YouthPolicyPage;
import com.team3.dataservice.collection.exception.YouthPolicyClientException;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class YouthPolicyClientTests {

    private final String apiKey = UUID.randomUUID().toString();
    private MockRestServiceServer server;
    private YouthPolicyClient client;

    @Test
    void createsClientInSpringWithoutABuilderBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(YouthPolicyClient.class)
                .withPropertyValues(
                        "collection.youth-policy.url=https://example.test/policies",
                        "collection.youth-policy.connect-timeout=5s",
                        "collection.youth-policy.read-timeout=20s")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(YouthPolicyClient.class));
                    assertTrue(context.getBeansOfType(RestClient.Builder.class).isEmpty());
                });
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/policies");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new YouthPolicyClient(builder.build(), apiKey);
    }

    @Test
    void fetchesOnePageAndPreservesUnknownRawFields() {
        server.expect(requestTo("https://example.test/policies?apiKeyNm=" + apiKey + "&pageNum=2&pageSize=100&pageType=1&rtnType=json"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"resultCode":200,"result":{"pagging":{"pageNum":2,"pageSize":100,"totCount":3000},
                        "youthPolicyList":[{"plcyNo":"123","unknownField":{"nested":[1,null,"원본"]}}]}}
                        """, MediaType.APPLICATION_JSON));

        YouthPolicyPage page = client.fetchPage(2, 100);

        assertEquals(3000, page.totalCount());
        assertEquals("123", page.records().getFirst().path("plcyNo").asString());
        assertEquals("원본", page.records().getFirst().path("unknownField").path("nested").get(2).asString());
        server.verify();
    }

    @Test
    void acceptsAnExplicitEmptyPageAndMissingTotalCount() {
        respond("""
                {"resultCode":"200","result":{"pagging":{"pageNum":"1","pageSize":"100"},
                "youthPolicyList":[]}}
                """);
        YouthPolicyPage page = client.fetchPage(1, 100);
        assertNull(page.totalCount());
        assertTrue(page.records().isEmpty());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "not json",
            "{\"resultCode\":200,\"result\":{\"pagging\":{\"pageNum\":1,\"pageSize\":100}}}",
            "{\"resultCode\":200,\"result\":{\"pagging\":{\"pageNum\":2,\"pageSize\":100},\"youthPolicyList\":[]}}",
            "{\"resultCode\":200,\"result\":{\"pagging\":{\"pageNum\":1,\"pageSize\":100,\"totCount\":-1},\"youthPolicyList\":[]}}",
            "{\"resultCode\":200,\"result\":{\"pagging\":{\"pageNum\":1,\"pageSize\":100},\"youthPolicyList\":[null]}}"
    })
    void rejectsMalformedResponsesInsteadOfReturningAnEmptyPage(String response) {
        respond(response);
        YouthPolicyClientException exception = assertThrows(YouthPolicyClientException.class,
                () -> client.fetchPage(1, 100));
        assertEquals("INVALID_RESPONSE", exception.getErrorCode());
        assertFalse(exception.isRetryable());
        server.verify();
    }

    @Test
    void rejectsAnApiErrorWithoutExposingTheExternalMessage() {
        respond("{\"resultCode\":401,\"resultMessage\":\"invalid " + apiKey + "\"}");
        YouthPolicyClientException exception = assertThrows(YouthPolicyClientException.class,
                () -> client.fetchPage(1, 100));
        assertEquals("API_ERROR", exception.getErrorCode());
        assertFalse(exception.isRetryable());
        assertFalse(exception.toString().contains(apiKey));
        assertNull(exception.getCause());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 429, 503})
    void reportsHttpErrorsAndWhetherTheyCanBeRetried(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status))
                .body(apiKey).contentType(MediaType.TEXT_PLAIN));
        YouthPolicyClientException exception = assertThrows(YouthPolicyClientException.class,
                () -> client.fetchPage(1, 100));
        assertEquals("HTTP_" + status, exception.getErrorCode());
        assertEquals(status == 429 || status >= 500, exception.isRetryable());
        assertFalse(exception.toString().contains(apiKey));
        server.verify();
    }

    @Test
    void reportsConnectionErrorsWithoutLeakingTheKeyThroughTheCause(CapturedOutput output) {
        // 실패 로그에는 요청 메타데이터만 남기고 원본 예외 메시지는 제외한다.
        server.expect(anything()).andRespond(withException(new IOException("URL contains " + apiKey)));
        YouthPolicyClientException exception = assertThrows(YouthPolicyClientException.class,
                () -> client.fetchPage(1, 100));
        assertEquals("CONNECTION_ERROR", exception.getErrorCode());
        assertTrue(exception.isRetryable());
        assertNull(exception.getCause());
        assertTrue(output.getOut().contains("source=YOUTH_POLICY"));
        assertTrue(output.getOut().contains("ResourceAccessException"));
        assertFalse(output.getAll().contains(apiKey));
        server.verify();
    }

    @Test
    void validatesInputsAndConfigurationBeforeSendingARequest() {
        assertThrows(IllegalArgumentException.class, () -> client.fetchPage(0, 100));
        assertThrows(IllegalArgumentException.class, () -> client.fetchPage(1, 0));
        YouthPolicyClient unconfigured = new YouthPolicyClient(RestClient.create(), "");
        assertThrows(IllegalStateException.class, () -> unconfigured.fetchPage(1, 100));
        server.verify();
    }

    private void respond(String response) {
        server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
