package com.team3.backend.collection.controller;

import com.team3.backend.client.DataServiceClient;
import com.team3.backend.collection.exception.CollectionErrorCode;
import com.team3.backend.global.exception.BusinessException;
import feign.FeignException;
import feign.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * 수집 관리자 API. 관리자 권한은 여기서 확인하고, 실제 처리는 DataService 내부 API(/internal/collection/**)에 맡긴다.
 * 경로·요청·응답·에러 형식은 서비스 분리 전과 같다: DataService 응답의 상태 코드·본문을 그대로 돌려주고,
 * Location 헤더만 관리자 API 경로로 바꾼다. DataService에 연결하지 못하면 503 (COLLECTION_008).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/collection")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
public class CollectionAdminController {

    static final String INTERNAL_PREFIX = "/internal/collection";
    static final String ADMIN_PREFIX = "/api/admin/collection";

    private final DataServiceClient dataService;

    @PostMapping("/syncs/{source}")
    public ResponseEntity<byte[]> startSync(@PathVariable String source,
                                            @RequestParam(required = false) String pageSize,
                                            @RequestParam(required = false) String maxPages) {
        return relay(() -> dataService.startSync(source, pageSize, maxPages));
    }

    /** 한국 시간 기준 접수일(startedAt)로 모든 상태의 작업을 최신순 조회한다. 날짜 형식은 yyyy-MM-dd */
    @GetMapping("/syncs")
    public ResponseEntity<byte[]> listSyncs(@RequestParam(required = false) String date) {
        return relay(() -> dataService.listSyncs(date));
    }

    @GetMapping("/syncs/{jobId}")
    public ResponseEntity<byte[]> getSync(@PathVariable String jobId) {
        return relay(() -> dataService.getSync(jobId));
    }

    @PostMapping("/raw-records")
    public ResponseEntity<byte[]> saveRawRecord(@RequestBody(required = false) String json) {
        return relay(() -> dataService.saveRawRecord(json));
    }

    @GetMapping("/raw-records/{rawRecordId}")
    public ResponseEntity<byte[]> getRawRecord(@PathVariable String rawRecordId) {
        return relay(() -> dataService.getRawRecord(rawRecordId));
    }

    private ResponseEntity<byte[]> relay(Supplier<Response> call) {
        try (Response response = call.get()) {
            HttpHeaders headers = new HttpHeaders();
            first(response, HttpHeaders.CONTENT_TYPE).ifPresent(value -> headers.set(HttpHeaders.CONTENT_TYPE, value));
            first(response, HttpHeaders.LOCATION).ifPresent(value -> headers.setLocation(adminLocation(value)));
            return new ResponseEntity<>(body(response), headers, response.status());
        } catch (FeignException | IOException e) {
            log.warn("DataService call failed: {}", e.toString());
            throw new BusinessException(CollectionErrorCode.DATA_SERVICE_UNAVAILABLE);
        }
    }

    private static byte[] body(Response response) throws IOException {
        if (response.body() == null) {
            return new byte[0];
        }
        try (InputStream in = response.body().asInputStream()) {
            return in.readAllBytes();
        }
    }

    private static java.util.Optional<String> first(Response response, String name) {
        Collection<String> values = response.headers().get(name);
        if (values == null) {
            values = response.headers().get(name.toLowerCase());
        }
        return values == null ? java.util.Optional.empty() : values.stream().findFirst();
    }

    /** DataService가 준 위치(/internal/collection/...)를 관리자 API 위치(/api/admin/collection/...)로 바꾼다 */
    static URI adminLocation(String location) {
        String path = URI.create(location).getPath();
        if (path != null && path.startsWith(INTERNAL_PREFIX)) {
            return URI.create(ADMIN_PREFIX + path.substring(INTERNAL_PREFIX.length()));
        }
        return URI.create(location);
    }
}
