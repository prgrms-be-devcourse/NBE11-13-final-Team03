package com.team3.backend.recommendation.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.recommendation.dto.RecommendationItemDto;
import com.team3.backend.recommendation.dto.RecommendationReasonsDto;
import com.team3.backend.recommendation.service.RecommendationService;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import com.team3.backend.user.domain.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 추천 경로의 인증·응답 순서·프로필 예외를 실제 보안 설정과 함께 검증한다. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class RecommendationControllerTest {

    private static final CustomUserDetails USER = new CustomUserDetails(7L, Role.USER, "session-1");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RecommendationService recommendationService;

    @Test
    void anonymousRecommendationIsUnauthorizedButPublicDetailRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/opportunities/recommendations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/opportunities/not-a-number"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(recommendationService);
    }

    @Test
    void authenticatedUserReceivesSelectionOrderAndReasons() throws Exception {
        when(recommendationService.recommend(7L)).thenReturn(List.of(item(9L, true), item(2L, false)));

        mockMvc.perform(get("/api/opportunities/recommendations").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].opportunityId").value(9))
                .andExpect(jsonPath("$[0].saved").value(true))
                .andExpect(jsonPath("$[0].regions[0].role").value("RESIDENCE"))
                .andExpect(jsonPath("$[0].regions[0].code").value("1100000000"))
                .andExpect(jsonPath("$[1].regions").isEmpty())
                .andExpect(jsonPath("$[0].eligibilityStatus").value("ELIGIBLE"))
                .andExpect(jsonPath("$[0].recommendationReasons.badges[0]").value("신청 조건 충족"))
                .andExpect(jsonPath("$[1].opportunityId").value(2))
                .andExpect(jsonPath("$[1].saved").value(false));
        verify(recommendationService).recommend(7L);
    }

    @Test
    void missingProfileUsesExistingErrorResponse() throws Exception {
        when(recommendationService.recommend(7L))
                .thenThrow(new BusinessException(EligibilityErrorCode.PROFILE_NOT_FOUND));

        mockMvc.perform(get("/api/opportunities/recommendations").with(user(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY_001"));
    }

    @Test
    void noRecommendationsReturnAnEmptyArray() throws Exception {
        when(recommendationService.recommend(7L)).thenReturn(List.of());

        mockMvc.perform(get("/api/opportunities/recommendations").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private static RecommendationItemDto item(Long id, boolean saved) {
        return new RecommendationItemDto(id, OpportunityType.YOUTH_POLICY, "정책", "일자리", "취업", null,
                saved ? List.of(new OpportunityDetailResponseDto.RegionItem(RegionRole.RESIDENCE, "SPECIFIC",
                        "1100000000", "서울", ValidationStatus.VALID)) : List.of(),
                ApplicationStatus.OPEN, EligibilityStatus.ELIGIBLE, LocalDate.of(2026, 11, 1), null,
                null, saved, new RecommendationReasonsDto(List.of("신청 조건 충족"),
                "저장된 최신 판정에서 신청 조건을 충족합니다."));
    }
}
