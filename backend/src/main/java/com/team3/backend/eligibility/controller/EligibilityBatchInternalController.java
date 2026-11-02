package com.team3.backend.eligibility.controller;

import com.team3.backend.eligibility.service.EligibilityBatchRefresher;
import com.team3.backend.eligibility.service.EligibilityBatchRefresher.RunResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 신청 가능성 판정 배치를 10분 주기를 기다리지 않고 즉시 실행하는 내부 API.
 * X-INTERNAL-KEY 헤더로 인증한다 (InternalApiKeyFilter). 실행이 끝날 때까지 기다렸다가 결과를 돌려준다.
 */
@Tag(name = "Eligibility Internal", description = "판정 배치 즉시 실행 (내부 키 필요)")
@RestController
@RequiredArgsConstructor
public class EligibilityBatchInternalController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ObjectProvider<EligibilityBatchRefresher> batchRefresher;

    public record BatchRunResponse(String status, Integer users, Integer failed, Integer judged, Long elapsedMs) {

        static BatchRunResponse completed(RunResult result) {
            return new BatchRunResponse("COMPLETED", result.users(), result.failed(), result.judged(), result.elapsedMs());
        }

        static BatchRunResponse of(String status) {
            return new BatchRunResponse(status, null, null, null, null);
        }
    }

    @Operation(summary = "판정 배치 즉시 실행", description = "판정 결과가 없거나 오래된 조건 묶음을 모든 사용자에 대해 지금 판정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "실행 완료"),
            @ApiResponse(responseCode = "401", description = "내부 키가 없거나 틀림"),
            @ApiResponse(responseCode = "409", description = "이미 실행 중 (ALREADY_RUNNING)"),
            @ApiResponse(responseCode = "503", description = "판정 배치가 꺼져 있음 (DISABLED)")
    })
    @PostMapping("/api/internal/eligibility/batch/refresh")
    public ResponseEntity<BatchRunResponse> refresh() {
        EligibilityBatchRefresher refresher = batchRefresher.getIfAvailable();
        if (refresher == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(BatchRunResponse.of("DISABLED"));
        }
        Optional<RunResult> result = refresher.tryRun(OffsetDateTime.now(KST));
        return result.map(r -> ResponseEntity.ok(BatchRunResponse.completed(r)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(BatchRunResponse.of("ALREADY_RUNNING")));
    }
}
