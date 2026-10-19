package com.team3.backend.preference.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.preference.dto.SavedOpportunitiesResponseDto;
import com.team3.backend.preference.service.PreferenceService;
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

    /** 공고를 저장하고 본문 없이 응답한다. */
    @PostMapping("/api/opportunities/{id}/save")
    public ResponseEntity<Void> save(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long id
    ) {
        preferenceService.save(userDetails.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 공고 저장을 해제하고 본문 없이 응답한다. */
    @DeleteMapping("/api/opportunities/{id}/save")
    public ResponseEntity<Void> unsave(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long id
    ) {
        preferenceService.unsave(userDetails.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 공고를 숨기고 본문 없이 응답한다. */
    @PostMapping("/api/opportunities/{id}/hide")
    public ResponseEntity<Void> hide(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long id
    ) {
        preferenceService.hide(userDetails.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 공고 숨김을 해제하고 본문 없이 응답한다. */
    @DeleteMapping("/api/opportunities/{id}/hide")
    public ResponseEntity<Void> unhide(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long id
    ) {
        preferenceService.unhide(userDetails.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 인증 사용자의 저장 공고를 페이지로 조회한다. */
    @GetMapping("/api/users/me/saved-opportunities")
    public ResponseEntity<SavedOpportunitiesResponseDto> getSavedOpportunities(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(preferenceService.getSavedOpportunities(
                userDetails.getUserId(), page, size));
    }
}
