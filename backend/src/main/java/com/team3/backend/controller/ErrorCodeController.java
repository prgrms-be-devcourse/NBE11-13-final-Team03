package com.team3.backend.controller;

import com.team3.backend.global.exception.ErrorCode;
import com.team3.backend.global.exception.ErrorCodeProvider;
import com.team3.backend.global.role.ApiRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(
        name = "Error Code",
        description = "도메인별 업무 오류 코드 조회 API"
)
@ApiRole
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/error-codes")
public class ErrorCodeController {

    private final List<ErrorCodeProvider<?>> errorCodeProviders;

    @GetMapping
    @Operation(
            summary = "에러 코드 목록 조회",
            description = "전체 도메인의 업무 오류 코드와 HTTP 상태, 메시지를 도메인별로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "에러 코드 목록 조회 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증되지 않은 사용자"
            )
    })
    @SecurityRequirement(name = "cookieAuth")
    public Map<String, List<ErrorCode>> getErrorCodes() {
        return errorCodeProviders.stream()
                .collect(Collectors.toMap(
                        ErrorCodeProvider::getDomain,
                        provider -> provider.getErrorCodes()
                                .stream()
                                .toList()
                ));
    }
}
