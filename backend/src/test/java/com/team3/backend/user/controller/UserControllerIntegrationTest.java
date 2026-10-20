package com.team3.backend.user.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.exception.UserErrorCode;
import com.team3.backend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void 회원_탈퇴에_성공하면_서비스를_호출하고_인증_쿠키를_만료한다()
            throws Exception {
        Long userId = 1L;

        MvcResult result = mockMvc.perform(
                        delete("/api/users/me")
                                .with(authentication(createAuthentication(userId)))
                                .with(csrf())
                )
                .andExpect(status().isNoContent())
                .andReturn();

        verify(userService).withdraw(userId);

        List<String> cookies = result.getResponse()
                .getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(cookies).hasSize(2);
        assertThat(cookies).anyMatch(cookie ->
                cookie.contains("access_token=")
                        && cookie.contains("Max-Age=0")
        );
        assertThat(cookies).anyMatch(cookie ->
                cookie.contains("refresh_token=")
                        && cookie.contains("Max-Age=0")
        );
    }

    @Test
    void 비로그인_사용자는_회원_탈퇴를_요청할_수_없다()
            throws Exception {
        mockMvc.perform(
                        delete("/api/users/me")
                                .with(csrf())
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void CSRF_토큰이_없으면_회원_탈퇴가_거부된다()
            throws Exception {
        mockMvc.perform(
                        delete("/api/users/me")
                                .with(authentication(createAuthentication(1L)))
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void 존재하지_않는_사용자는_404를_반환하고_쿠키를_삭제하지_않는다()
            throws Exception {
        Long userId = 99L;

        doThrow(new BusinessException(UserErrorCode.USER_NOT_FOUND))
                .when(userService)
                .withdraw(userId);

        MvcResult result = mockMvc.perform(
                        delete("/api/users/me")
                                .with(authentication(createAuthentication(userId)))
                                .with(csrf())
                )
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(
                result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
        ).isEmpty();
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
}