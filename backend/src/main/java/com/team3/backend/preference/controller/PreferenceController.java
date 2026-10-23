package com.team3.backend.preference.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.domain.entity.UserBehaviorEventType;
import com.team3.backend.behavior.domain.entity.UserBehaviorSource;
import com.team3.backend.behavior.service.PreferenceBehaviorRecorder;
import com.team3.backend.preference.dto.SavedOpportunitiesResponseDto;
import com.team3.backend.preference.service.PreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증 사용자의 공고 저장·숨김과 저장 목록 API를 제공한다. */
@Tag(name = "Preference", description = "공고 저장·숨김 API")
@SecurityRequirement(name = "cookieAuth")
@RestController
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService preferenceService;
    private final PreferenceBehaviorRecorder behaviorRecorder;

    /** 공고를 저장하고 본문 없이 응답한다. */
    @Operation(summary = "공고 저장", description = "이미 저장된 공고도 멱등하게 처리한다. 숨김 상태는 변경하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "저장 성공, 응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "400", description = "공고 ID 형식 오류 (COMMON_002)"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "사용자 또는 공고를 찾을 수 없는 경우 (USER_001, PREFERENCE_001)")
    })
    @PostMapping("/api/opportunities/{id}/save")
    public ResponseEntity<Void> save(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "저장할 공고 ID") @PathVariable Long id,
            @Parameter(description = "행동 출처, 생략 시 OTHER")
            @RequestParam(defaultValue = "OTHER") UserBehaviorSource source
    ) {
        Long userId = userDetails.getUserId();
        if (preferenceService.save(userId, id)) {
            behaviorRecorder.record(userId, id, UserBehaviorEventType.SAVE, source);
        }
        return ResponseEntity.noContent().build();
    }

    /** 공고 저장을 해제하고 본문 없이 응답한다. */
    @Operation(summary = "공고 저장 해제", description = "저장하지 않은 공고도 멱등하게 처리한다. 숨김 상태는 변경하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "저장 해제 성공, 응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "400", description = "공고 ID 형식 오류 (COMMON_002)"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "사용자 또는 공고를 찾을 수 없는 경우 (USER_001, PREFERENCE_001)")
    })
    @DeleteMapping("/api/opportunities/{id}/save")
    public ResponseEntity<Void> unsave(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "저장을 해제할 공고 ID") @PathVariable Long id
    ) {
        preferenceService.unsave(userDetails.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 공고를 숨기고 본문 없이 응답한다. */
    @Operation(summary = "공고 숨김", description = "이미 숨긴 공고도 멱등하게 처리한다. 저장 상태는 변경하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "숨김 성공, 응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "400", description = "공고 ID 형식 오류 (COMMON_002)"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "사용자 또는 공고를 찾을 수 없는 경우 (USER_001, PREFERENCE_001)")
    })
    @PostMapping("/api/opportunities/{id}/hide")
    public ResponseEntity<Void> hide(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "숨길 공고 ID") @PathVariable Long id,
            @Parameter(description = "행동 출처, 생략 시 OTHER")
            @RequestParam(defaultValue = "OTHER") UserBehaviorSource source
    ) {
        Long userId = userDetails.getUserId();
        if (preferenceService.hide(userId, id)) {
            behaviorRecorder.record(userId, id, UserBehaviorEventType.HIDE, source);
        }
        return ResponseEntity.noContent().build();
    }

    /** 공고 숨김을 해제하고 본문 없이 응답한다. */
    @Operation(summary = "공고 숨김 해제", description = "숨기지 않은 공고도 멱등하게 처리한다. 저장 상태는 변경하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "숨김 해제 성공, 응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "400", description = "공고 ID 형식 오류 (COMMON_002)"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "사용자 또는 공고를 찾을 수 없는 경우 (USER_001, PREFERENCE_001)")
    })
    @DeleteMapping("/api/opportunities/{id}/hide")
    public ResponseEntity<Void> unhide(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "숨김을 해제할 공고 ID") @PathVariable Long id
    ) {
        preferenceService.unhide(userDetails.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 인증 사용자의 저장 공고를 페이지로 조회한다. */
    @Operation(summary = "내 저장 공고 목록", description = "숨김 여부와 관계없이 저장된 공고를 savedAt 내림차순, 저장 상태 ID 내림차순으로 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공, 저장 공고가 없으면 빈 content를 반환한다"),
            @ApiResponse(responseCode = "400", description = "잘못된 페이지 요청 (COMMON_001, COMMON_002)"),
            @ApiResponse(responseCode = "401", description = "Access Token이 없거나 유효하지 않은 경우"),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없는 경우 (USER_001)")
    })
    @GetMapping("/api/users/me/saved-opportunities")
    public ResponseEntity<SavedOpportunitiesResponseDto> getSavedOpportunities(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기, 1~100") @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(preferenceService.getSavedOpportunities(
                userDetails.getUserId(), page, size));
    }
}
