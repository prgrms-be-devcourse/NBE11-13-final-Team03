package com.team3.backend.auth.controller;

import com.team3.backend.auth.cookie.TokenCookieManager;
import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.auth.service.AuthService;
import com.team3.backend.auth.service.TokenService;
import com.team3.backend.global.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Auth",
        description = "인증 및 토큰 관리 API"
)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {
    private final TokenService tokenService;
    private final AuthService authService;
    private final TokenCookieManager tokenCookieManager;

    @Operation(
            summary = "로그아웃",
            description = """
                    로그인한 사용자를 로그아웃합니다.
                    
                    서버에 저장된 Refresh Token을 삭제하고,
                    Access Token과 Refresh Token 쿠키를 제거합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "로그아웃 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증되지 않은 사용자 또는 유효하지 않은 Access Token"
            )
    })
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal CustomUserDetails user,
            HttpServletResponse response
    ) {
        authService.logout(
                user.getUserId(),
                user.getSessionId()
        );

        tokenCookieManager.deleteTokenCookies(response);

        return ResponseEntity.noContent().build();
    }


    @Operation(
            summary = "Access Token 재발급",
            description = """
                    Refresh Token을 검증하여 새로운 Access Token과 Refresh Token을 발급합니다.
                    
                    Refresh Token은 HttpOnly Cookie에서 전달받습니다.
                    재발급된 Access Token과 Refresh Token도 HttpOnly Cookie로 설정됩니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "토큰 재발급 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = """
                            인증 실패
                            
                            - Refresh Token이 존재하지 않음
                            - Refresh Token이 유효하지 않음
                            - Refresh Token이 만료됨
                            - 저장된 Refresh Token과 일치하지 않음
                            
                            일치하지 않는 경우를 제외하면 Access Token·Refresh Token 쿠키를 함께 제거합니다.
                            """
            )
    })
    @PostMapping("/reissue")
    public ResponseEntity<Void> reissue(
            @CookieValue(name = "refresh_token", required = false)
            String refreshToken,
            HttpServletResponse response
    ) {

        TokenService.TokenPair tokenPair;
        try {
            tokenPair = tokenService.reissueToken(refreshToken);
        } catch (BusinessException failure) {
            // A refresh cookie that can never succeed again must not keep public requests
            // bouncing on 401. On a hash mismatch the browser may already hold cookies rotated
            // by a concurrent request, so those are left untouched.
            if (failure.getErrorCode() != AuthErrorCode.REFRESH_TOKEN_MISMATCH) {
                tokenCookieManager.deleteTokenCookies(response);
            }
            throw failure;
        }

        tokenCookieManager.addTokenCookies(response, tokenPair);

        return ResponseEntity.ok().build();
    }
}