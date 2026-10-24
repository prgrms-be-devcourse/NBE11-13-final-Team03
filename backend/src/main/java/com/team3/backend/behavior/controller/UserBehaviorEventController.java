package com.team3.backend.behavior.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.dto.UserBehaviorEventRequestDto;
import com.team3.backend.behavior.service.UserBehaviorEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 인증 사용자의 화면 행동 이벤트 수집 API를 제공한다. */
@Tag(name = "Behavior", description = "사용자 행동 이벤트 수집 API")
@SecurityRequirement(name = "cookieAuth")
@RestController
@RequiredArgsConstructor
public class UserBehaviorEventController {

    private final UserBehaviorEventService eventService;

    /** FE에서 확인한 노출·상세 진입·외부 링크 클릭을 기록한다. */
    @Operation(summary = "공고 행동 이벤트 기록", description = "인증 사용자의 IMPRESSION, DETAIL_VIEW, EXTERNAL_LINK_CLICK만 기록한다. 노출은 추천 출처만 허용한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "기록 성공"),
            @ApiResponse(responseCode = "400", description = "이벤트 종류·출처·링크 유형 오류"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            @ApiResponse(responseCode = "404", description = "공고를 찾을 수 없음")
    })
    @PostMapping("/api/opportunities/{id}/events")
    public ResponseEntity<Void> record(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long id,
            @Valid @RequestBody UserBehaviorEventRequestDto request
    ) {
        eventService.record(userDetails.getUserId(), id, request);
        return ResponseEntity.noContent().build();
    }
}
