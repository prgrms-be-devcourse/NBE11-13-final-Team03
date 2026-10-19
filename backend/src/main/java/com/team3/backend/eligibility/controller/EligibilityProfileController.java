package com.team3.backend.eligibility.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.dto.EligibilityProfilePatchRequest;
import com.team3.backend.eligibility.dto.EligibilityProfileResponse;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(
        name = "Eligibility Profile",
        description = "신청 가능성 판정에 쓰는 내 판정 조건(프로필) API"
)
@RestController
@RequestMapping("/api/users/me/eligibility-profile")
@RequiredArgsConstructor
public class EligibilityProfileController {

    private final EligibilityProfileService profileService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "내 판정 조건 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "판정 조건을 아직 등록하지 않은 경우 (ELIGIBILITY_001)")
    })
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping
    public ResponseEntity<EligibilityProfileResponse> get(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(profileService.get(userDetails.getUserId()));
    }

    @Operation(
            summary = "내 판정 조건 등록·전체 교체",
            description = """
                    없으면 만들고, 있으면 요청 내용으로 전체를 교체합니다.
                    보내지 않은 선택 항목은 미입력(null)이 됩니다. 일부만 바꾸려면 PATCH를 쓰세요.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 오류 (COMMON_001, ELIGIBILITY_004, ELIGIBILITY_005)"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PutMapping
    public ResponseEntity<EligibilityProfileResponse> put(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody EligibilityProfileUpdateRequest request
    ) {
        return ResponseEntity.ok(profileService.put(userDetails.getUserId(), request));
    }

    @Operation(
            summary = "내 판정 조건 부분 수정",
            description = """
                    JSON Merge Patch 방식입니다.
                    - 보내지 않은 필드: 기존 값 유지
                    - null을 보낸 필드: 미입력으로 변경
                    - 값을 보낸 필드: 그 값으로 변경
                    수정한 결과 전체를 PUT과 같은 기준으로 검증합니다 (필수 항목을 null로 보내면 400).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 오류 (ELIGIBILITY_004, ELIGIBILITY_005)"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "판정 조건을 아직 등록하지 않은 경우 (ELIGIBILITY_001)")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PatchMapping
    public ResponseEntity<EligibilityProfileResponse> patch(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "바꿀 필드만 보냅니다. 필드 구조는 PUT 요청과 같습니다.",
                    content = @Content(schema = @Schema(implementation = EligibilityProfileUpdateRequest.class)))
            @RequestBody Map<String, Object> body
    ) {
        // "보내지 않음"과 "null"을 구분하려고 Map으로 받는다 (EligibilityProfilePatchRequest 참고)
        EligibilityProfilePatchRequest request = EligibilityProfilePatchRequest.fromJson(body, objectMapper);
        return ResponseEntity.ok(profileService.patch(userDetails.getUserId(), request));
    }
}
