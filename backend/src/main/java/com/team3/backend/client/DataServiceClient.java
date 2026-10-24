package com.team3.backend.client;

import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * DataService(수집·정규화) 내부 API. 내부 키(X-INTERNAL-KEY)는 DataServiceFeignConfig가 붙인다.
 * 값은 검증하지 않고 문자열 그대로 넘긴다 (검증과 에러 응답은 DataService가 기존 관리자 API와 같은 형식으로 만든다).
 * 상태 코드를 그대로 전달하려고 feign.Response를 받는다 (4xx·5xx에서도 예외를 던지지 않음). 연결 실패만 FeignException.
 */
@FeignClient(name = "data-service", url = "${yon.data-service.base-url}", configuration = DataServiceFeignConfig.class)
public interface DataServiceClient {

    @PostMapping("/internal/collection/syncs/{source}")
    Response startSync(@PathVariable("source") String source,
                       @RequestParam(value = "pageSize", required = false) String pageSize,
                       @RequestParam(value = "maxPages", required = false) String maxPages);

    @GetMapping("/internal/collection/syncs")
    Response listSyncs(@RequestParam(value = "date", required = false) String date);

    @GetMapping("/internal/collection/syncs/{jobId}")
    Response getSync(@PathVariable("jobId") String jobId);

    @PostMapping(value = "/internal/collection/raw-records", consumes = MediaType.APPLICATION_JSON_VALUE)
    Response saveRawRecord(@RequestBody(required = false) String json);

    @GetMapping("/internal/collection/raw-records/{rawRecordId}")
    Response getRawRecord(@PathVariable("rawRecordId") String rawRecordId);
}
