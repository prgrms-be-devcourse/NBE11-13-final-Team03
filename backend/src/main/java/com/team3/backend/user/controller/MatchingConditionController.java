package com.team3.backend.user.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.dto.EligibilityProfilePatchRequest;
import com.team3.backend.eligibility.dto.EligibilityProfileResponse;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.user.mapper.MatchingConditionPatchMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ObjectNode;

@Tag(
        name = "Matching Condition",
        description = "사용자 맞춤 조건 관리 API"
)
@RestController
@RequestMapping("/api/users/me/matching-conditions")
@RequiredArgsConstructor
public class MatchingConditionController {

    private final EligibilityProfileService eligibilityProfileService;
    private final MatchingConditionPatchMapper patchMapper;

    @Operation(
            summary = "맞춤 조건 조회",
            description = """
                    현재 로그인한 사용자가 등록한 맞춤 조건을 조회합니다.

                    저장된 맞춤 조건이 없는 경우 404 응답을 반환합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "맞춤 조건 조회 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증되지 않은 사용자"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "등록된 맞춤 조건이 없음"
            )
    })
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping
    public ResponseEntity<EligibilityProfileResponse> getMatchingConditions(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        EligibilityProfileResponse response =
                eligibilityProfileService.get(userDetails.getUserId());

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "맞춤 조건 최초 등록",
            description = """
                현재 로그인한 사용자의 맞춤 조건을 최초 등록합니다.

                이미 맞춤 조건이 등록되어 있으면 409 응답을 반환합니다.
                """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "맞춤 조건 등록 성공"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "입력값 또는 지역 코드가 올바르지 않음"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증되지 않은 사용자"
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 맞춤 조건이 등록되어 있음"
            )
    })
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping
    public ResponseEntity<EligibilityProfileResponse> createMatchingConditions(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody EligibilityProfileUpdateRequest request
    ) {
        EligibilityProfileResponse response =
                eligibilityProfileService.create(
                        userDetails.getUserId(),
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @Operation(
            summary = "맞춤 조건 부분 수정",
            description = """
                현재 로그인한 사용자의 맞춤 조건을 부분 수정합니다.

                요청에 포함하지 않은 필드는 기존 값을 유지합니다.
                선택 항목에 null을 명시하면 해당 값을 미입력 상태로 변경합니다.
                필수 항목은 null로 변경할 수 없습니다.
                """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "맞춤 조건 수정 성공"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "수정한 맞춤 조건이 유효하지 않음"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증되지 않은 사용자"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "등록된 맞춤 조건이 없음"
            )
    })
    @SecurityRequirement(name = "cookieAuth")
    @PatchMapping
    public ResponseEntity<EligibilityProfileResponse> patchMatchingConditions(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody ObjectNode patchDocument
    ) {
        EligibilityProfilePatchRequest request =
                patchMapper.from(patchDocument);

        EligibilityProfileResponse response =
                eligibilityProfileService.patch(
                        userDetails.getUserId(),
                        request
                );

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "맞춤 조건 삭제",
            description = """
                현재 로그인한 사용자의 맞춤 조건을 삭제합니다.

                맞춤 조건으로 계산된 기존 신청 가능성 판정 결과도 함께 삭제합니다.
                """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "맞춤 조건 삭제 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증되지 않은 사용자"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "등록된 맞춤 조건이 없음"
            )
    })
    @SecurityRequirement(name = "cookieAuth")
    @DeleteMapping
    public ResponseEntity<Void> deleteMatchingConditions(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        eligibilityProfileService.delete(
                userDetails.getUserId()
        );

        return ResponseEntity.noContent().build();
    }
}