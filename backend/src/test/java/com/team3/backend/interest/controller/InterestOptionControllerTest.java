package com.team3.backend.interest.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.interest.dto.InterestCategoryOptionResponse;
import com.team3.backend.interest.dto.InterestOptionsResponse;
import com.team3.backend.interest.dto.InterestSubCategoryOptionResponse;
import com.team3.backend.interest.service.InterestOptionService;
import com.team3.backend.user.domain.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class InterestOptionControllerTest {

    private static final String OPTIONS_URL =
            "/api/interests/options";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InterestOptionService interestOptionService;

    @Test
    void 로그인_사용자는_관심분야_계층목록을_조회한다()
            throws Exception {
        when(interestOptionService.getOptions())
                .thenReturn(new InterestOptionsResponse(List.of(
                        new InterestCategoryOptionResponse(
                                "교육･직업훈련",
                                List.of(
                                        new InterestSubCategoryOptionResponse(
                                                "정보통신",
                                                List.of("정보기술", "통신기술")
                                        )
                                )
                        ),
                        new InterestCategoryOptionResponse(
                                "일자리",
                                List.of(
                                        new InterestSubCategoryOptionResponse(
                                                "취업지원",
                                                List.of()
                                        )
                                )
                        )
                )));

        mockMvc.perform(get(OPTIONS_URL)
                        .with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(2))
                .andExpect(jsonPath("$.categories[0].category")
                        .value("교육･직업훈련"))
                .andExpect(jsonPath(
                        "$.categories[0].subCategories[0].subCategory"
                ).value("정보통신"))
                .andExpect(jsonPath(
                        "$.categories[0].subCategories[0].detailCategories.length()"
                ).value(2))
                .andExpect(jsonPath(
                        "$.categories[0].subCategories[0].detailCategories[0]"
                ).value("정보기술"))
                .andExpect(jsonPath(
                        "$.categories[0].subCategories[0].detailCategories[1]"
                ).value("통신기술"))
                .andExpect(jsonPath("$.categories[1].category")
                        .value("일자리"))
                .andExpect(jsonPath(
                        "$.categories[1].subCategories[0].detailCategories"
                ).isEmpty());
    }

    @Test
    void 선택할_분류가_없으면_빈_배열을_반환한다()
            throws Exception {
        when(interestOptionService.getOptions())
                .thenReturn(new InterestOptionsResponse(List.of()));

        mockMvc.perform(get(OPTIONS_URL)
                        .with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.categories").isEmpty());
    }

    @Test
    void 비로그인_요청은_401을_반환한다()
            throws Exception {
        mockMvc.perform(get(OPTIONS_URL))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(interestOptionService);
    }

    private CustomUserDetails principal() {
        return new CustomUserDetails(
                1L,
                Role.USER,
                "interest-options-test-session"
        );
    }
}