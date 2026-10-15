package com.team3.backend.collection.client;

import com.team3.backend.collection.dto.external.YouthPolicyPage;
import com.team3.backend.collection.exception.YouthPolicyClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
public class YouthPolicyClient {

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public YouthPolicyClient(
            @Value("${collection.youth-policy.url}") String url,
            @Value("${collection.youth-policy.api-key:}") String apiKey,
            @Value("${collection.youth-policy.connect-timeout:5s}") String connectTimeout,
            @Value("${collection.youth-policy.read-timeout:20s}") String readTimeout) {
        this(createRestClient(url, DurationStyle.detectAndParse(connectTimeout),
                DurationStyle.detectAndParse(readTimeout)), apiKey);
    }

    YouthPolicyClient(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    public YouthPolicyPage fetchPage(int pageNumber, int pageSize) {
        if (pageNumber < 1 || pageSize < 1) {
            throw new IllegalArgumentException("페이지 번호와 크기는 1 이상이어야 합니다.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("YOUTH_POLICY_API_KEY 설정이 필요합니다.");
        }

        JsonNode response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.queryParam("apiKeyNm", "{apiKey}")
                            .queryParam("pageNum", pageNumber)
                            .queryParam("pageSize", pageSize)
                            .queryParam("pageType", "1")
                            .queryParam("rtnType", "json")
                            .build(apiKey))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, result) -> {
                        int status = result.getStatusCode().value();
                        throw new YouthPolicyClientException("HTTP_" + status,
                                status == 429 || status >= 500);
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException exception) {
            throw new YouthPolicyClientException("CONNECTION_ERROR", true);
        } catch (RestClientException exception) {
            throw new YouthPolicyClientException("INVALID_RESPONSE", false);
        }
        return parsePage(response, pageNumber, pageSize);
    }

    private YouthPolicyPage parsePage(JsonNode response, int pageNumber, int pageSize) {
        if (response == null || !response.isObject()) {
            throw invalidResponse();
        }
        JsonNode code = response.path("resultCode");
        if (!(code.isTextual() || code.isIntegralNumber())) {
            throw invalidResponse();
        }
        if (!"200".equals(code.asString())) {
            boolean retryable = "429".equals(code.asString())
                    || code.asString().matches("5[0-9]{2}");
            throw new YouthPolicyClientException("API_ERROR", retryable);
        }

        JsonNode result = response.path("result");
        JsonNode records = result.path("youthPolicyList");
        JsonNode paging = result.path("pagging");
        if (!records.isArray() || !paging.isObject()
                || !Integer.valueOf(pageNumber).equals(readInteger(paging.path("pageNum")))
                || !Integer.valueOf(pageSize).equals(readInteger(paging.path("pageSize")))
                || records.size() > pageSize) {
            throw invalidResponse();
        }

        Integer totalCount = readInteger(paging.path("totCount"));
        List<JsonNode> rawRecords = new ArrayList<>();
        for (JsonNode record : records) {
            if (!record.isObject()) {
                throw invalidResponse();
            }
            rawRecords.add(record);
        }
        return new YouthPolicyPage(totalCount, rawRecords);
    }

    private Integer readInteger(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
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

    private YouthPolicyClientException invalidResponse() {
        return new YouthPolicyClientException("INVALID_RESPONSE", false);
    }

    private static RestClient createRestClient(String url,
            Duration connectTimeout, Duration readTimeout) {
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
