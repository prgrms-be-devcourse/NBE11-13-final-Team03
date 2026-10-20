package com.team3.backend.collection.controller;

import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.dto.response.RawRecordResponse;
import com.team3.backend.collection.service.RawRecordService;
import com.team3.backend.global.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/collection/raw-records")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Collection Raw Record", description = "관리자 전용 외부 API 원본 스냅샷 저장 및 조회 API")
@SecurityRequirement(name = "cookieAuth")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "필수값 누락, 잘못된 JSON·ID·수집 시각",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
        @ApiResponse(responseCode = "403", description = "관리자 권한 부족 또는 CSRF 검증 실패", content = @Content),
        @ApiResponse(responseCode = "404", description = "수집 실행·출처 또는 원본 레코드 없음",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "저장소 처리 또는 서버 내부 오류",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class RawRecordController {
    private final RawRecordService service;

    @PostMapping
    @Operation(summary = "외부 API 원본 저장", description = """
            RUNNING 상태의 수집 실행에 원본 JSON을 저장합니다. 출처는 syncId로 결정하며 해시는 서버가 계산합니다.
            동일 원본 재저장은 기존 스냅샷의 최종 관측 시각을 갱신하고, 변경된 원본은 새 스냅샷으로 보존합니다.
            이 API는 정규화 데이터나 정제 요청을 생성하지 않습니다.
            ADMIN 인증 쿠키와 XSRF-TOKEN 쿠키에 대응하는 X-XSRF-TOKEN 헤더가 필요합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "원본 저장 또는 기존 스냅샷 갱신 성공"),
            @ApiResponse(responseCode = "409", description = "진행 중이 아닌 수집 실행",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public RawRecordResponse save(@RequestBody RawRecordSaveRequest request) {
        return service.save(request);
    }

    @GetMapping("/{rawRecordId}")
    @Operation(summary = "외부 API 원본 조회", description = "ADMIN 인증이 필요합니다. 정규화 이전 원본 JSON과 출처·외부 식별자·관측 시각을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "원본 조회 성공")
    public RawRecordResponse get(@Parameter(description = "원본 스냅샷 ID", schema = @Schema(implementation = Long.class, minimum = "1"))
            @PathVariable Long rawRecordId) {
        return service.get(rawRecordId);
    }
}
