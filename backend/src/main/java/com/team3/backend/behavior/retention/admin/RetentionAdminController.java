package com.team3.backend.behavior.retention.admin;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.ExecuteRequest;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.ExecuteResponse;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.PreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 행동 로그 보관 정리 ADMIN API. 실행 순서·보호 장치는 docs/user-behavior-events-partitioning.md 8절을 따른다. */
@Tag(name = "Behavior Retention Admin", description = "행동 로그 월 파티션 보관 정리 관리자 API")
@SecurityRequirement(name = "cookieAuth")
@RestController
@RequestMapping("/api/admin/behavior-events/retention")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
public class RetentionAdminController {

    private final RetentionAdminService service;

    @Operation(summary = "보관 정리 미리보기", description = "현재 정책·기준일·보관 시작일과 생성·삭제 예정 파티션을 조회한다. DB를 바꾸지 않는다.")
    @GetMapping("/preview")
    public PreviewResponse preview(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return service.preview(userDetails.getUserId());
    }

    @Operation(summary = "보관 정리 실행", description = "미리보기의 planToken과 실행 직전 계획이 같을 때만 생성·삭제한다. "
            + "yon.behavior.retention.admin-execute-enabled=true일 때만 허용된다.")
    @PostMapping("/execute")
    public ExecuteResponse execute(@AuthenticationPrincipal CustomUserDetails userDetails,
                                   @Valid @RequestBody ExecuteRequest request) {
        return service.execute(userDetails.getUserId(), request.planToken());
    }
}
