package com.team3.backend.collection.client;

import com.team3.backend.collection.dto.external.Employment24Page;
import com.team3.backend.collection.dto.external.Employment24SearchRequest;
import com.team3.backend.collection.exception.Employment24ClientException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
public class Employment24Client {
    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public Employment24Client(
            @Value("${collection.employment24.url}") String url,
            @Value("${collection.employment24.api-key:}") String apiKey,
            @Value("${collection.employment24.connect-timeout:5s}") String connectTimeout,
            @Value("${collection.employment24.read-timeout:20s}") String readTimeout) {
        this(createRestClient(url, DurationStyle.detectAndParse(connectTimeout),
                DurationStyle.detectAndParse(readTimeout)), apiKey);
    }

    Employment24Client(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    /** NCS 코드 하나의 한 페이지를 조회한다. 코드·페이지 순회와 저장은 수집 서비스가 담당한다. */
    public Employment24Page fetchPage(Employment24SearchRequest search) {
        Objects.requireNonNull(search, "고용24 조회 조건이 필요합니다.");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("EMPLOYMENT24_API_KEY 설정이 필요합니다.");
        }
        JsonNode response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.queryParam("authKey", "{apiKey}")
                            .queryParam("returnType", "JSON")
                            .queryParam("outType", "1")
                            .queryParam("pageNum", search.pageNum())
                            .queryParam("pageSize", search.pageSize())
                            .queryParam("srchNcs1", search.srchNcs1())
                            .queryParam("srchTraStDt", search.srchTraStDt().format(DateTimeFormatter.BASIC_ISO_DATE))
                            .queryParam("srchTraEndDt", search.srchTraEndDt().format(DateTimeFormatter.BASIC_ISO_DATE))
                            .queryParam("sort", "ASC")
                            .queryParam("sortCol", "2")
                            .build(apiKey))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, result) -> {
                        int status = result.getStatusCode().value();
                        throw new Employment24ClientException("HTTP_" + status,
                                status == 429 || status >= 500);
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException exception) {
            // 요청 URL에 포함된 인증키가 원인 예외를 통해 노출되지 않게 한다.
            throw new Employment24ClientException("CONNECTION_ERROR", true);
        } catch (RestClientException exception) {
            throw invalidResponse();
        }
        return parsePage(response, search);
    }

    private Employment24Page parsePage(JsonNode response, Employment24SearchRequest search) {
        if (response == null || !response.isObject()) {
            throw invalidResponse();
        }
        int totalCount = readInteger(response.path("scn_cnt"));
        int pageNum = readInteger(response.path("pageNum"));
        int pageSize = readInteger(response.path("pageSize"));
        JsonNode records = response.path("srchList");
        if (pageNum != search.pageNum() || pageSize != search.pageSize()
                || !records.isArray() || records.size() > pageSize) {
            throw invalidResponse();
        }
        List<JsonNode> rawRecords = new ArrayList<>();
        for (JsonNode record : records) {
            if (!record.isObject()) {
                throw invalidResponse();
            }
            rawRecords.add(record);
        }
        return new Employment24Page(totalCount, response.path("pageNum").asString(),
                response.path("pageSize").asString(), rawRecords);
    }

    private int readInteger(JsonNode value) {
        if (!(value.isTextual() || value.isIntegralNumber())) {
            throw invalidResponse();
        }
        try {
            int number = Integer.parseInt(value.asString());
            if (number < 0) {
                throw invalidResponse();
            }
            return number;
        } catch (NumberFormatException exception) {
            throw invalidResponse();
        }
    }

    private Employment24ClientException invalidResponse() {
        return new Employment24ClientException("INVALID_RESPONSE", false);
    }

    private static RestClient createRestClient(String url, Duration connectTimeout, Duration readTimeout) {
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("API 타임아웃은 0보다 커야 합니다.");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
}
