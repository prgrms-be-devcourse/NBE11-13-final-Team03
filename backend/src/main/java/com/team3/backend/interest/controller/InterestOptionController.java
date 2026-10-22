package com.team3.backend.interest.controller;

import com.team3.backend.interest.dto.InterestOptionsResponse;
import com.team3.backend.interest.service.InterestOptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자가 선택할 수 있는 관심 분야 분류 목록을 제공한다.
 */
@Tag(
        name = "User Interest",
        description = "사용자 관심 분야 설정 API"
)
@SecurityRequirement(name = "cookieAuth")
@RestController
@RequiredArgsConstructor
public class InterestOptionController {

    private final InterestOptionService interestOptionService;

    @Operation(
            summary = "관심 분야 선택 목록 조회",
            description = """
                    정규화가 완료된 공고에 실제로 존재하는 관심 분야 목록을
                    대분류 → 중분류 → 세분류 계층으로 조회합니다.

                    청년정책:
                    - category: 온통청년 대분류
                    - subCategory: 온통청년 중분류
                    - detailCategories: 빈 배열

                    직업훈련:
                    - category: 교육･직업훈련
                    - subCategory: NCS 1차 분류명
                    - detailCategories: NCS 2차 분류명 목록

                    관심 분야 저장 시 이 API가 반환한 문자열을 변경하지 않고
                    그대로 전달해야 정확하게 매칭됩니다.
                    
                    관심 분야 저장 API(PUT /api/users/me/interests)에 전달할 때는
                    다음 타입을 사용합니다.
                    
                    - category: CATEGORY
                    - subCategory: SUB_CATEGORY
                    - detailCategories의 각 항목: DETAIL_CATEGORY
                    
                    code에는 선택한 분류 문자열을 변경하지 않고 그대로 전달합니다.
                    이 목록은 분류명 기반이며 NCS 코드 목록은 제공하지 않습니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "관심 분야 선택 목록 조회 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Access Token이 없거나 유효하지 않은 경우",
                    content = @Content
            )
    })
    @GetMapping("/api/interests/options")
    public ResponseEntity<InterestOptionsResponse> getOptions() {
        return ResponseEntity.ok(
                interestOptionService.getOptions()
        );
    }
}