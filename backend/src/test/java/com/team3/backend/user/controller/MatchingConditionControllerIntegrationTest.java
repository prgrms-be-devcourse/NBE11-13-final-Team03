package com.team3.backend.user.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.domain.entity.EducationLevel;
import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.eligibility.dto.EligibilityProfileResponse;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.eligibility.dto.EligibilityProfilePatchRequest;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.global.exception.BusinessException;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class MatchingConditionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EligibilityProfileService eligibilityProfileService;

    @Test
    void 맞춤_조건을_조회한다() throws Exception {
        Long userId = 1L;

        when(eligibilityProfileService.get(userId))
                .thenReturn(response());

        mockMvc.perform(
                        get("/api/users/me/matching-conditions")
                                .with(authentication(
                                        createAuthentication(userId)
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthDate")
                        .value("1998-05-15"))
                .andExpect(jsonPath("$.residenceRegionCode")
                        .value("1168000000"))
                .andExpect(jsonPath("$.employmentStatus")
                        .value("UNEMPLOYED"))
                .andExpect(jsonPath("$.educationLevel")
                        .value("UNIVERSITY"));

        verify(eligibilityProfileService).get(userId);
    }

    @Test
    void 비로그인_사용자는_맞춤_조건을_조회할_수_없다()
            throws Exception {
        mockMvc.perform(
                        get("/api/users/me/matching-conditions")
                )
                .andExpect(status().isUnauthorized());
    }

    private EligibilityProfileResponse response() {
        return new EligibilityProfileResponse(
                LocalDate.of(1998, 5, 15),
                "1168000000",
                EmploymentStatus.UNEMPLOYED,
                EducationLevel.UNIVERSITY,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.parse("2026-10-19T10:00:00+09:00")
        );
    }

    private Authentication createAuthentication(Long userId) {
        CustomUserDetails principal = new CustomUserDetails(
                userId,
                Role.USER,
                "session-1"
        );

        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    @Test
    void 맞춤_조건을_최초_등록한다() throws Exception {
        Long userId = 1L;

        when(eligibilityProfileService.create(
                eq(userId),
                any(EligibilityProfileUpdateRequest.class)
        )).thenReturn(response());

        mockMvc.perform(
                        post("/api/users/me/matching-conditions")
                                .with(authentication(
                                        createAuthentication(userId)
                                ))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "birthDate": "1998-05-15",
                                  "residenceRegionCode": "1168000000",
                                  "employmentStatus": "UNEMPLOYED",
                                  "educationLevel": "UNIVERSITY"
                                }
                                """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.birthDate")
                        .value("1998-05-15"))
                .andExpect(jsonPath("$.residenceRegionCode")
                        .value("1168000000"));

        ArgumentCaptor<EligibilityProfileUpdateRequest> captor =
                ArgumentCaptor.forClass(
                        EligibilityProfileUpdateRequest.class
                );

        verify(eligibilityProfileService)
                .create(eq(userId), captor.capture());

        EligibilityProfileUpdateRequest request =
                captor.getValue();

        assertThat(request.birthDate())
                .isEqualTo(LocalDate.of(1998, 5, 15));
        assertThat(request.residenceRegionCode())
                .isEqualTo("1168000000");
        assertThat(request.employmentStatus())
                .isEqualTo(EmploymentStatus.UNEMPLOYED);
        assertThat(request.educationLevel())
                .isEqualTo(EducationLevel.UNIVERSITY);
    }

    @Test
    void 필수값이_없는_맞춤_조건은_등록할_수_없다()
            throws Exception {
        Long userId = 1L;

        mockMvc.perform(
                        post("/api/users/me/matching-conditions")
                                .with(authentication(
                                        createAuthentication(userId)
                                ))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "residenceRegionCode": ""
                                }
                                """)
                )
                .andExpect(status().isBadRequest());

        verify(eligibilityProfileService, never())
                .create(
                        anyLong(),
                        any(EligibilityProfileUpdateRequest.class)
                );
    }

    @Test
    void 맞춤_조건의_전달된_필드만_부분_수정한다()
            throws Exception {
        Long userId = 1L;

        when(eligibilityProfileService.patch(
                eq(userId),
                any(EligibilityProfilePatchRequest.class)
        )).thenReturn(response());

        mockMvc.perform(
                        patch("/api/users/me/matching-conditions")
                                .with(authentication(
                                        createAuthentication(userId)
                                ))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "employmentStatus": "UNEMPLOYED",
                                  "schoolRegionCode": null
                                }
                                """)
                )
                .andExpect(status().isOk());

        ArgumentCaptor<EligibilityProfilePatchRequest> captor =
                ArgumentCaptor.forClass(
                        EligibilityProfilePatchRequest.class
                );

        verify(eligibilityProfileService)
                .patch(eq(userId), captor.capture());

        EligibilityProfilePatchRequest request =
                captor.getValue();

        // 요청에 없는 필드 → null → 기존 값 유지
        assertThat(request.birthDate())
                .isNull();

        // 값을 전달한 필드 → Optional.of(...)
        assertThat(request.employmentStatus())
                .contains(EmploymentStatus.UNEMPLOYED);

        // 명시적인 null → Optional.empty() → 기존 값 삭제
        assertThat(request.schoolRegionCode())
                .isEmpty();
    }

    @Test
    void 잘못된_enum값으로_맞춤_조건을_수정할_수_없다()
            throws Exception {
        Long userId = 1L;

        mockMvc.perform(
                        patch("/api/users/me/matching-conditions")
                                .with(authentication(
                                        createAuthentication(userId)
                                ))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "employmentStatus": "INVALID_STATUS"
                                }
                                """)
                )
                .andExpect(status().isBadRequest());

        verify(eligibilityProfileService, never())
                .patch(
                        anyLong(),
                        any(EligibilityProfilePatchRequest.class)
                );
    }

    @Test
    void 맞춤_조건을_삭제한다() throws Exception {
        Long userId = 1L;

        mockMvc.perform(
                        delete("/api/users/me/matching-conditions")
                                .with(authentication(
                                        createAuthentication(userId)
                                ))
                                .with(csrf())
                )
                .andExpect(status().isNoContent());

        verify(eligibilityProfileService)
                .delete(userId);
    }

    @Test
    void 삭제할_맞춤_조건이_없으면_404를_반환한다()
            throws Exception {
        Long userId = 1L;

        doThrow(new BusinessException(
                EligibilityErrorCode.PROFILE_NOT_FOUND
        ))
                .when(eligibilityProfileService)
                .delete(userId);

        mockMvc.perform(
                        delete("/api/users/me/matching-conditions")
                                .with(authentication(
                                        createAuthentication(userId)
                                ))
                                .with(csrf())
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void 이미_맞춤_조건이_있으면_최초_등록은_409를_반환한다()
            throws Exception {
        Long userId = 1L;

        doThrow(new BusinessException(
                EligibilityErrorCode.PROFILE_ALREADY_EXISTS
        ))
                .when(eligibilityProfileService)
                .create(
                        eq(userId),
                        any(EligibilityProfileUpdateRequest.class)
                );

        mockMvc.perform(
                        post("/api/users/me/matching-conditions")
                                .with(authentication(
                                        createAuthentication(userId)
                                ))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "birthDate": "1998-05-15",
                                  "residenceRegionCode": "1168000000",
                                  "employmentStatus": "UNEMPLOYED",
                                  "educationLevel": "UNIVERSITY"
                                }
                                """)
                )
                .andExpect(status().isConflict());
    }
}