package com.team3.backend.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityIntegrationTest.TestEndpointController.class)
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("비회원도 기회 목록을 조회할 수 있다")
    void opportunityListIsPublic() throws Exception {
        mockMvc.perform(get("/api/opportunities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("비회원도 기회 상세를 조회할 수 있다")
    void opportunityDetailIsPublic() throws Exception {
        mockMvc.perform(get("/api/opportunities/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증이 필요한 API는 비회원 접근을 거부한다")
    void protectedApiRejectsAnonymousUser() throws Exception {
        mockMvc.perform(get("/api/security-test/protected"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "USER")
    @DisplayName("인증 사용자라도 CSRF 토큰 없이 상태 변경을 요청하면 거부한다")
    void stateChangingRequestWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/api/security-test/change"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "USER")
    @DisplayName("인증 사용자에게 CSRF 토큰이 있으면 상태 변경을 허용한다")
    void stateChangingRequestWithCsrfIsAllowed() throws Exception {
        mockMvc.perform(
                        post("/api/security-test/change")
                                .with(csrf())
                )
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("내부 API는 설정대로 CSRF 검사를 제외한다")
    void internalApiDoesNotRequireCsrf() throws Exception {
        mockMvc.perform(
                        post("/api/internal/security-test")
                                .header(
                                        "X-INTERNAL-KEY",
                                        "test-internal-api-key"
                                )
                )
                .andExpect(status().isOk());
    }

    /*
     * 실제 OpportunityController가 아직 없더라도
     * SecurityConfig의 경로별 권한을 검증하기 위한 테스트 전용 Controller
     */
    @RestController
    public static class TestEndpointController {

        @GetMapping("/api/opportunities/{opportunityId}")
        public String opportunity(
                @PathVariable Long opportunityId
        ) {
            return "opportunity-" + opportunityId;
        }

        @GetMapping("/api/security-test/protected")
        public String protectedEndpoint() {
            return "protected";
        }

        @PostMapping("/api/security-test/change")
        public String change() {
            return "changed";
        }

        @PostMapping("/api/internal/security-test")
        public String internal() {
            return "internal";
        }
    }
}
