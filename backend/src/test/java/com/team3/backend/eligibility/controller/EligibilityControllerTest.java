package com.team3.backend.eligibility.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.dto.EligibilityListResponse;
import com.team3.backend.eligibility.dto.EligibilityProfilePatchRequest;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.eligibility.service.EligibilityQueryService;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * part 2 컨트롤러의 요청 매핑·인증 사용자 전달·요청 본문 변환·오류 응답을 검증한다. 서비스는 목으로 대체한다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class EligibilityControllerTest {

    private static final CustomUserDetails ME = new CustomUserDetails(7L, Role.USER, "session-1");

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private EligibilityProfileService profileService;
    @MockitoBean
    private EligibilityQueryService queryService;

    @Test
    void 비로그인은_401() throws Exception {
        mockMvc.perform(get("/api/users/me/eligibility-profile"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/opportunities/1/eligibility"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 프로필이_없으면_404와_오류_코드() throws Exception {
        when(profileService.get(7L)).thenThrow(new BusinessException(EligibilityErrorCode.PROFILE_NOT_FOUND));

        mockMvc.perform(get("/api/users/me/eligibility-profile").with(user(ME)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY_001"));
    }

    @Test
    void PUT은_로그인_사용자_ID와_요청을_서비스에_넘긴다() throws Exception {
        mockMvc.perform(put("/api/users/me/eligibility-profile").with(user(ME)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"birthDate":"1998-05-15","residenceRegionCode":"1168000000",
                                 "employmentStatus":"UNEMPLOYED","educationLevel":"UNIVERSITY"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<EligibilityProfileUpdateRequest> captor = ArgumentCaptor.forClass(EligibilityProfileUpdateRequest.class);
        verify(profileService).put(eq(7L), captor.capture());
        assertThat(captor.getValue().birthDate()).isEqualTo(LocalDate.of(1998, 5, 15));
        assertThat(captor.getValue().residenceRegionCode()).isEqualTo("1168000000");
    }

    @Test
    void PUT_필수값이_없으면_400과_필드_오류() throws Exception {
        mockMvc.perform(put("/api/users/me/eligibility-profile").with(user(ME)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"residenceRegionCode\":\"1168000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(profileService);
    }

    @Test
    void PATCH는_보내지_않은_필드와_null을_보낸_필드를_구분한다() throws Exception {
        mockMvc.perform(patch("/api/users/me/eligibility-profile").with(user(ME)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"residenceRegionCode":"1144000000","specialTargets":null,
                                 "militaryService":{"status":"COMPLETED","months":21}}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<EligibilityProfilePatchRequest> captor = ArgumentCaptor.forClass(EligibilityProfilePatchRequest.class);
        verify(profileService).patch(eq(7L), captor.capture());
        EligibilityProfilePatchRequest r = captor.getValue();
        assertThat(r.birthDate()).as("보내지 않은 필드 → 유지(null)").isNull();
        assertThat(r.residenceRegionCode()).as("값을 보낸 필드").contains("1144000000");
        assertThat(r.specialTargets()).as("null을 보낸 필드 → 미입력으로 변경(Optional.empty)").isEqualTo(Optional.empty());
        assertThat(r.militaryService()).isPresent();
    }

    @Test
    void PATCH도_CSRF_토큰이_없으면_거부한다() throws Exception {
        mockMvc.perform(patch("/api/users/me/eligibility-profile").with(user(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(profileService);
    }

    @Test
    void 목록_판정은_페이지_기본값_0과_20을_쓴다() throws Exception {
        when(queryService.evaluateAll(eq(7L), any(Integer.class), any(Integer.class)))
                .thenReturn(new EligibilityListResponse(List.of(), 0, 20, 0, 0, false, OffsetDateTime.now()));

        mockMvc.perform(get("/api/users/me/opportunities/eligibility").with(user(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));

        verify(queryService).evaluateAll(7L, 0, 20);
    }

    @Test
    void 단건_판정은_경로의_기회_ID를_넘긴다() throws Exception {
        mockMvc.perform(get("/api/opportunities/42/eligibility").with(user(ME)))
                .andExpect(status().isOk());

        verify(queryService).evaluate(7L, 42L);
    }
}
