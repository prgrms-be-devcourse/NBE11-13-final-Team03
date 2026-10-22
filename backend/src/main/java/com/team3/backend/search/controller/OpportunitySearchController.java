package com.team3.backend.search.controller;

import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
import com.team3.backend.search.dto.OpportunitySearchSort;
import com.team3.backend.search.service.OpportunitySearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 공개 공고 검색과 인증 사용자 신청 가능성 필터 요청을 받는 API다. */
@Tag(name = "Opportunity Search", description = "청년정책·직업훈련 통합 검색 API")
@RestController
@RequiredArgsConstructor
public class OpportunitySearchController {

    private final OpportunitySearchService searchService;

    /** 공고 조건·신청 가능성·유형별 날짜 범위·정렬 기준으로 검색하고 페이지 결과를 반환한다. */
    @Operation(
            summary = "청년정책·직업훈련 통합 검색",
            description = """
                    정규화된 공고를 제목·유형·지역·분류·모집 상태·유형별 날짜 조건의 AND로 검색한다. 숨김 공고도 포함한다.
                    일반 검색은 비로그인도 가능하지만 eligibilityStatuses를 지정하면 로그인이 필요하다.
                    신청 가능성 필터는 저장된 최신 판정 결과만 사용하며 결과가 없거나 오래된 공고는 제외한다. 검색 중 새로 판정하지 않는다.
                    직업훈련의 OPEN 검색은 실제 모집기간 확정이 아니라 교육 시작일이 오늘 또는 이후인 훈련을 포함하는 검색 규칙이다.
                    날짜 범위는 양 끝을 포함한다. deadlineFrom/To는 YOUTH_POLICY, startDateFrom/To는 VOCATIONAL_TRAINING에서만 사용할 수 있다.
                    DEADLINE_PRIORITY는 청년정책을 OPEN → UPCOMING → ALWAYS_OPEN → CLOSED → UNKNOWN 순으로 정렬하며, 같은 상태에서는 마감일과 ID를 사용한다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검색 성공, 결과가 없으면 빈 content를 반환한다"),
            @ApiResponse(responseCode = "400", description = "잘못된 enum·지역 코드·날짜 범위·정렬/유형 조합 또는 페이지 요청 (COMMON_001, COMMON_002)"),
            @ApiResponse(responseCode = "401", description = "신청 가능성 필터 요청 시 Access Token이 없거나 유효하지 않은 경우")
    })
    @GetMapping("/api/opportunities")
    public ResponseEntity<OpportunitySearchResponseDto> search(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "제목의 대소문자 무관 부분 일치 검색어")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "공고 유형: YOUTH_POLICY 또는 VOCATIONAL_TRAINING")
            @RequestParam(required = false) OpportunityType type,
            @Parameter(description = "대분류, 정확히 일치한다")
            @RequestParam(required = false) String category,
            @Parameter(description = "중분류, 정확히 일치한다")
            @RequestParam(required = false) String subCategory,
            @Parameter(description = "세 번째 분류 (직업훈련: NCS 중분류)")
            @RequestParam(required = false) String detailCategory,
            @Parameter(description = "지역 코드. 정책은 거주 자격 지역과 전국 대상, 훈련은 실제 교육 지역을 조회한다. 시·도 선택은 하위 지역을 포함한다")
            @RequestParam(required = false) String region,
            @Parameter(description = "모집 상태: UPCOMING, OPEN, ALWAYS_OPEN, CLOSED, UNKNOWN. 훈련 OPEN은 개강 예정 검색 규칙을 적용한다")
            @RequestParam(required = false) ApplicationStatus applicationStatus,
            @Parameter(description = "신청 가능성 상태: ELIGIBLE, POSSIBLY_ELIGIBLE, NOT_ELIGIBLE, UNKNOWN. 쉼표로 복수 선택할 수 있으며 로그인 필수다")
            @RequestParam(required = false) List<EligibilityStatus> eligibilityStatuses,
            @Parameter(description = "청년정책 신청 마감일 시작(포함), YYYY-MM-DD. type=YOUTH_POLICY 필수다")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineFrom,
            @Parameter(description = "청년정책 신청 마감일 끝(포함), YYYY-MM-DD. type=YOUTH_POLICY 필수다")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineTo,
            @Parameter(description = "직업훈련 교육 시작일 시작(포함), YYYY-MM-DD. type=VOCATIONAL_TRAINING 필수다")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateFrom,
            @Parameter(description = "직업훈련 교육 시작일 끝(포함), YYYY-MM-DD. type=VOCATIONAL_TRAINING 필수다")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateTo,
            @Parameter(description = "LATEST(기본, 최초 등록일 최신순), OLDEST(오래된순), DEADLINE_PRIORITY(정책 마감 임박순: OPEN, UPCOMING, ALWAYS_OPEN, CLOSED, UNKNOWN 순), START_DATE_ASC(훈련 시작일순). 전용 정렬에는 해당 type이 필수다")
            @RequestParam(defaultValue = "LATEST") OpportunitySearchSort sort,
            @Parameter(description = "0부터 시작하는 페이지 번호")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기, 1~100")
            @RequestParam(defaultValue = "20") int size
    ) {
        if (eligibilityStatuses != null && !eligibilityStatuses.isEmpty() && userDetails == null) {
            throw new BusinessException(AuthErrorCode.ACCESS_TOKEN_NOT_FOUND);
        }
        return ResponseEntity.ok(searchService.search(
                keyword, type, category, subCategory, detailCategory, region, applicationStatus,
                userDetails == null ? null : userDetails.getUserId(), eligibilityStatuses,
                deadlineFrom, deadlineTo, startDateFrom, startDateTo, sort, page, size));
    }
}
