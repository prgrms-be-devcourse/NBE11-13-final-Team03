package com.team3.backend.interest.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.interest.dto.UserInterestsResponse;
import com.team3.backend.interest.dto.UserInterestsUpdateRequest;
import com.team3.backend.interest.service.UserInterestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 사용자의 관심 분야 조회·전체 교체 API를 제공한다.
 */
@Tag(
        name = "User Interest",
        description = "사용자 관심 분야 설정 API"
)
@SecurityRequirement(name = "cookieAuth")
@RestController
@RequestMapping("/api/users/me/interests")
@RequiredArgsConstructor
public class UserInterestController {

    private final UserInterestService userInterestService;

    /**
     * 현재 사용자가 설정한 관심 분야를 조회한다.
     */
    @Operation(
            summary = "내 관심 분야 조회",
            description = """
                    인증된 사용자의 관심 분야를 조회합니다.

                    관심 분야는 다음 코드 체계를 사용합니다.
                    - CATEGORY: 온통청년 정책 대분류
                    - SUB_CATEGORY: 온통청년 정책 중분류
                    - NCS: 고용24 직업훈련 NCS 코드

                    등록된 관심 분야가 없으면 빈 interests 배열을 반환합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "관심 분야 조회 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Access Token이 없거나 유효하지 않은 경우",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자를 찾을 수 없는 경우 (USER_001)",
                    content = @Content
            )
    })
    @GetMapping
    public ResponseEntity<UserInterestsResponse> getInterests(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(
                userInterestService.getInterests(userDetails.getUserId())
        );
    }

    /**
     * 현재 사용자의 관심 분야를 요청 목록으로 전체 교체한다.
     */
    @Operation(
            summary = "내 관심 분야 전체 교체",
            description = """
                    인증된 사용자의 관심 분야를 요청 목록으로 전체 교체합니다.

                    요청에 포함되지 않은 기존 관심 분야는 삭제되고,
                    새로 포함된 관심 분야는 추가됩니다.

                    빈 interests 배열을 전달하면 모든 관심 분야를 해제합니다.
                    관심 분야는 최대 30개까지 설정할 수 있습니다.

                    상태 변경 요청이므로 CSRF Token이 필요합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "관심 분야 교체 성공"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                            요청값 검증 실패 (COMMON_001)
                            - interests가 null인 경우
                            - 관심 분야가 30개를 초과한 경우
                            - type 또는 code가 없는 경우
                            - code가 공백이거나 100자를 초과한 경우
                            - 동일한 type과 code가 중복된 경우
                            """,
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Access Token이 없거나 유효하지 않은 경우",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "CSRF Token이 없거나 유효하지 않은 경우",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자를 찾을 수 없는 경우 (USER_001)",
                    content = @Content
            )
    })
    @PutMapping
    public ResponseEntity<UserInterestsResponse> replaceInterests(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody UserInterestsUpdateRequest request
    ) {
        return ResponseEntity.ok(
                userInterestService.replaceInterests(
                        userDetails.getUserId(),
                        request
                )
        );
    }
}