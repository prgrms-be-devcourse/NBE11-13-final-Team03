package com.team3.backend.region.client;

import com.team3.backend.region.dto.StanReginCdResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 법정동코드 API 호출. 전체 페이지를 순서대로 받아 행 목록을 반환한다. 저장은 하지 않는다.
 */
@Component
public class RegionCodeApiClient {

    private final RestClient restClient;
    private final RegionCodeApiProperties properties;

    @Autowired
    public RegionCodeApiClient(ObjectProvider<RestClient.Builder> builderProvider, RegionCodeApiProperties properties) {
        this(builderProvider.getIfAvailable(RestClient::builder), properties);
    }

    RegionCodeApiClient(RestClient.Builder builder, RegionCodeApiProperties properties) {
        // 이 API는 JSON 본문을 Content-Type: text/html로 보내므로 JSON으로 바로잡아 읽는다
        this.restClient = builder
                .requestInterceptor((request, body, execution) -> {
                    ClientHttpResponse response = execution.execute(request, body);
                    MediaType contentType = response.getHeaders().getContentType();
                    return contentType != null && contentType.isCompatibleWith(MediaType.TEXT_HTML)
                            ? new JsonContentTypeResponse(response)
                            : response;
                })
                .build();
        this.properties = properties;
    }

    public List<StanReginCdResponse.Row> fetchAll() {
        if (properties.getServiceKey() == null || properties.getServiceKey().isBlank()) {
            throw new IllegalStateException("region-code-api.service-key(REGION_CODE_API_KEY)가 설정되지 않았습니다.");
        }
        List<StanReginCdResponse.Row> rows = new ArrayList<>();
        int pageNo = 1;
        Integer totalCount;
        do {
            StanReginCdResponse page = fetchPage(pageNo);
            totalCount = page.totalCount();
            List<StanReginCdResponse.Row> pageRows = page.rows();
            if (pageRows.isEmpty()) {
                break;
            }
            rows.addAll(pageRows);
            pageNo++;
        } while (totalCount != null && rows.size() < totalCount);
        return rows;
    }

    StanReginCdResponse fetchPage(int pageNo) {
        URI uri = pageUri(pageNo);
        StanReginCdResponse response;
        try {
            // 완성된 URI를 넘겨 Spring이 다시 인코딩하지 않게 한다 (curl --data-urlencode와 같은 결과)
            response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(StanReginCdResponse.class);
        } catch (RestClientException e) {
            // 인증키 오류 등은 JSON이 아닌 XML로 응답하는 경우가 있어 해석 단계에서 실패할 수 있다
            throw new IllegalStateException("법정동코드 API 호출 또는 응답 해석에 실패했습니다. pageNo=" + pageNo
                    + ", url=" + masked(uri), e);
        }
        if (response == null) {
            throw new IllegalStateException("법정동코드 API 응답이 비어 있습니다. pageNo=" + pageNo);
        }
        StanReginCdResponse.Result result = response.result();
        if (result != null && !StanReginCdResponse.SUCCESS_CODE.equals(result.resultCode())) {
            throw new IllegalStateException("법정동코드 API 오류: " + result.resultCode() + " " + result.resultMsg());
        }
        return response;
    }

    /**
     * 공공데이터포털 인코딩 키는 그대로 쓰고(이미 '%2B' 등으로 인코딩됨),
     * 디코딩 키가 들어오면 '+', '/', '=' 까지 퍼센트 인코딩한다.
     */
    URI pageUri(int pageNo) {
        String key = properties.getServiceKey().trim();
        String encodedKey = key.contains("%") ? key : URLEncoder.encode(key, StandardCharsets.UTF_8);
        return URI.create(properties.getBaseUrl()
                + "?ServiceKey=" + encodedKey
                + "&pageNo=" + pageNo
                + "&numOfRows=" + properties.getPageSize()
                + "&type=json");
    }

    private static final class JsonContentTypeResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final HttpHeaders headers = new HttpHeaders();

        private JsonContentTypeResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
            delegate.getHeaders().forEach(headers::put);
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static String masked(URI uri) {
        return uri.toString().replaceAll("ServiceKey=[^&]*", "ServiceKey=****");
    }
}
