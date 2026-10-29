package com.team3.dataservice.collection.client;

import com.team3.dataservice.collection.dto.external.Employment24SearchRequest;
import com.team3.dataservice.collection.exception.Employment24ClientException;
import java.io.IOException;
import java.util.UUID;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class Employment24ClientTests {
    private final String apiKey = UUID.randomUUID() + "+/=";
    private MockRestServiceServer server;
    private Employment24Client client;
    private final Employment24SearchRequest search = Employment24SearchRequest.forNextSixMonths(
            "06", LocalDate.of(2026, 10, 15), 1, 100);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/training");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new Employment24Client(builder.build(), apiKey);
    }

    @Test
    void sendsNcsAndSixMonthDatesAndPreservesRawFields() {
        server.expect(requestTo("https://example.test/training?authKey=" + apiKey.replace("+/=", "%2B%2F%3D") + "&returnType=JSON&outType=1&pageNum=1&pageSize=100&srchNcs1=06&srchTraStDt=20261015&srchTraEndDt=20270415&sort=ASC&sortCol=2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"scn_cnt":300,"pageNum":"1","pageSize":"100","srchList":[
                          {"trprId":"AIG123","trprDegr":"01","ncsCd":"06010108",
                           "eiEmplRate3":"","unknown":{"values":[1,null,"원본"]}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var page = client.fetchPage(search);
        assertEquals(300, page.totalCount());
        var record = page.srchList().getFirst();
        assertEquals("06010108", record.path("ncsCd").asString());
        assertEquals("", record.path("eiEmplRate3").asString());
        assertTrue(record.path("unknown").path("values").get(1).isNull());
        assertEquals("원본", record.path("unknown").path("values").get(2).asString());
        server.verify();
    }

    @Test
    void acceptsAnExplicitEmptyPageAndNumericPaging() {
        respond("{\"scn_cnt\":\"0\",\"pageNum\":1,\"pageSize\":100,\"srchList\":[]}");
        var page = client.fetchPage(search);
        assertEquals(0, page.totalCount());
        assertTrue(page.srchList().isEmpty());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "not json",
            "{\"error\":\"invalid %s\"}",
            "{\"scn_cnt\":0,\"pageNum\":1,\"pageSize\":100}",
            "{\"scn_cnt\":0,\"pageNum\":2,\"pageSize\":100,\"srchList\":[]}",
            "{\"scn_cnt\":0,\"pageNum\":1,\"pageSize\":20,\"srchList\":[]}",
            "{\"scn_cnt\":-1,\"pageNum\":1,\"pageSize\":100,\"srchList\":[]}",
            "{\"scn_cnt\":2147483648,\"pageNum\":1,\"pageSize\":100,\"srchList\":[]}",
            "{\"scn_cnt\":1.5,\"pageNum\":1,\"pageSize\":100,\"srchList\":[]}",
            "{\"pageNum\":1,\"pageSize\":100,\"srchList\":[]}",
            "{\"scn_cnt\":1,\"pageNum\":1,\"pageSize\":100,\"srchList\":[null]}",
            "{\"scn_cnt\":1,\"pageNum\":1,\"pageSize\":100,\"srchList\":[\"record\"]}"
    })
    void rejectsMalformedOrErrorResponsesWithoutLeakingKey(String response) {
        respond(response.formatted(apiKey));
        var error = assertThrows(Employment24ClientException.class, () -> client.fetchPage(search));
        assertEquals("INVALID_RESPONSE", error.getErrorCode());
        assertFalse(error.isRetryable());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(apiKey));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 403, 429, 503})
    void classifiesHttpErrors(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)).body(apiKey));
        var error = assertThrows(Employment24ClientException.class, () -> client.fetchPage(search));
        assertEquals("HTTP_" + status, error.getErrorCode());
        assertEquals(status == 429 || status >= 500, error.isRetryable());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(apiKey));
        server.verify();
    }

    @Test
    void classifiesConnectionErrorsWithoutKeepingTheCause(CapturedOutput output) {
        // 실패 로그에는 요청 메타데이터만 남기고 원본 예외 메시지는 제외한다.
        server.expect(anything()).andRespond(withException(new IOException("URL contains " + apiKey)));
        var error = assertThrows(Employment24ClientException.class, () -> client.fetchPage(search));
        assertEquals("CONNECTION_ERROR", error.getErrorCode());
        assertTrue(error.isRetryable());
        assertNull(error.getCause());
        assertTrue(output.getOut().contains("source=EMPLOYMENT24"));
        assertTrue(output.getOut().contains("ResourceAccessException"));
        assertFalse(output.getAll().contains(apiKey));
        server.verify();
    }

    @Test
    void rejectsMoreRecordsThanRequested() {
        respond("{\"scn_cnt\":2,\"pageNum\":1,\"pageSize\":1,\"srchList\":[{},{}]}");
        var request = Employment24SearchRequest.forNextSixMonths("06", search.srchTraStDt(), 1, 1);
        assertThrows(Employment24ClientException.class, () -> client.fetchPage(request));
        server.verify();
    }

    @Test
    void validatesConfigurationBeforeCallingApi() {
        assertThrows(NullPointerException.class, () -> client.fetchPage(null));
        var unconfigured = new Employment24Client(RestClient.create(), " ");
        assertThrows(IllegalStateException.class, () -> unconfigured.fetchPage(search));
        assertThrows(IllegalArgumentException.class, () ->
                new Employment24Client("https://example.test", apiKey, "0s", "20s"));
        server.verify();
    }

    @Test
    void createsSpringBeanWithoutABuilderBeanOrApiKey() {
        new ApplicationContextRunner().withUserConfiguration(Employment24Client.class)
                .withPropertyValues("collection.employment24.url=https://example.test/training")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(Employment24Client.class));
                });
    }

    private void respond(String response) {
        server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
