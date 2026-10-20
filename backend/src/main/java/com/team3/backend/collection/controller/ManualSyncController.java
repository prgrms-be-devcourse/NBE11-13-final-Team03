package com.team3.backend.collection.controller;

import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.dto.response.SyncJobResponse;
import com.team3.backend.collection.dto.response.SyncStartResponse;
import com.team3.backend.collection.service.ManualSyncService;
import com.team3.backend.collection.service.SyncJobService;
import com.team3.backend.global.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/collection/syncs")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Collection Sync", description = "관리자 전용 수집 최신화 실행 및 상태 조회 API")
@SecurityRequirement(name = "cookieAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
        @ApiResponse(responseCode = "403", description = "관리자 권한 부족 또는 CSRF 검증 실패", content = @Content),
        @ApiResponse(responseCode = "500", description = "저장소 처리 또는 서버 내부 오류",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class ManualSyncController {
    private final ManualSyncService manualSync;
    private final SyncJobService jobs;

    @PostMapping("/{source}")
    @Operation(summary = "수동 수집 최신화 시작", description = """
            온통청년(YOUTH_POLICY) 또는 고용24(EMPLOYMENT24) 수집을 비동기로 시작합니다.
            202는 접수 성공이며 수집 완료를 의미하지 않습니다. Location의 작업 조회 API로 결과를 확인합니다.
            동일 출처의 대기·실행 중 작업이 있거나 출처가 비활성화되어 있으면 409를 반환합니다.
            ADMIN 인증 쿠키와 XSRF-TOKEN 쿠키에 대응하는 X-XSRF-TOKEN 헤더가 필요합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "최신화 접수 성공",
                    headers = @Header(name = "Location", description = "작업 조회 URL",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = "잘못된 출처 또는 페이지 범위",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "실행 중인 작업 또는 비활성 출처",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "최신화 실행기를 사용할 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SyncStartResponse> start(
            @Parameter(description = "수집 출처", required = true) @PathVariable CollectionSource source,
            @Parameter(description = "페이지 크기", schema = @Schema(implementation = Integer.class, minimum = "1", maximum = "100", defaultValue = "100"))
            @RequestParam(defaultValue = "100") int pageSize,
            @Parameter(description = "최대 조회 페이지 수", schema = @Schema(implementation = Integer.class, minimum = "1", maximum = "1000", defaultValue = "1000"))
            @RequestParam(defaultValue = "1000") int maxPages) {
        var response = manualSync.start(source, pageSize, maxPages);
        return ResponseEntity.accepted().location(URI.create("/api/admin/collection/syncs/" + response.jobId()))
                .body(response);
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "수집 작업 상태 및 결과 조회", description = "ADMIN 인증이 필요합니다. 진행 상태, 신규·변경·동일 원본 수와 페이지·레코드 실패 내역을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작업 조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 작업 ID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "수집 작업 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SyncJobResponse get(@Parameter(description = "수집 작업 ID", schema = @Schema(implementation = Long.class, minimum = "1"))
            @PathVariable Long jobId) {
        return jobs.get(jobId);
    }
}
