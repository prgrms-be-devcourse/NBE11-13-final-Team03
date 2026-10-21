package com.team3.backend.interest.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.interest.domain.repository.UserInterestRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class UserInterestControllerTest {

    private static final String INTEREST_URL =
            "/api/users/me/interests";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserInterestRepository userInterestRepository;

    @Test
    void 관심분야를_전체교체하고_조회한다() throws Exception {
        Long userId = createUser(401L);
        CustomUserDetails principal = principal(userId);

        String request = """
                {
                  "interests": [
                    {
                      "type": "NCS",
                      "code": "20010101"
                    },
                    {
                      "type": "CATEGORY",
                      "code": "  일자리  "
                    },
                    {
                      "type": "SUB_CATEGORY",
                      "code": "취업지원"
                    }
                  ]
                }
                """;

        mockMvc.perform(put(INTEREST_URL)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(3))
                .andExpect(jsonPath("$.interests[0].type")
                        .value("CATEGORY"))
                .andExpect(jsonPath("$.interests[0].code")
                        .value("일자리"))
                .andExpect(jsonPath("$.interests[1].type")
                        .value("NCS"))
                .andExpect(jsonPath("$.interests[1].code")
                        .value("20010101"))
                .andExpect(jsonPath("$.interests[2].type")
                        .value("SUB_CATEGORY"))
                .andExpect(jsonPath("$.interests[2].code")
                        .value("취업지원"));

        mockMvc.perform(get(INTEREST_URL)
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(3))
                .andExpect(jsonPath("$.interests[0].type")
                        .value("CATEGORY"))
                .andExpect(jsonPath("$.interests[0].code")
                        .value("일자리"))
                .andExpect(jsonPath("$.interests[1].type")
                        .value("NCS"))
                .andExpect(jsonPath("$.interests[2].type")
                        .value("SUB_CATEGORY"));

        assertEquals(
                3,
                userInterestRepository
                        .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(userId)
                        .size()
        );
    }

    @Test
    void 빈_배열로_관심분야를_전부_해제한다() throws Exception {
        Long userId = createUser(402L);
        CustomUserDetails principal = principal(userId);

        mockMvc.perform(put(INTEREST_URL)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interests": [
                                    {
                                      "type": "CATEGORY",
                                      "code": "주거"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(1));

        mockMvc.perform(put(INTEREST_URL)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interests": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(0));

        assertTrue(
                userInterestRepository
                        .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(userId)
                        .isEmpty()
        );
    }

    @Test
    void 관심분야가_없으면_빈_배열을_조회한다() throws Exception {
        Long userId = createUser(403L);

        mockMvc.perform(get(INTEREST_URL)
                        .with(user(principal(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(0));
    }

    @Test
    void 비로그인_요청은_거부한다() throws Exception {
        mockMvc.perform(get(INTEREST_URL))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put(INTEREST_URL)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interests": []
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void CSRF_토큰이_없는_변경요청은_거부한다() throws Exception {
        Long userId = createUser(404L);

        mockMvc.perform(put(INTEREST_URL)
                        .with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interests": []
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 동일한_관심분야가_중복되면_400을_반환한다()
            throws Exception {
        Long userId = createUser(405L);

        mockMvc.perform(put(INTEREST_URL)
                        .with(user(principal(userId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interests": [
                                    {
                                      "type": "CATEGORY",
                                      "code": "일자리"
                                    },
                                    {
                                      "type": "CATEGORY",
                                      "code": "  일자리  "
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("COMMON_001"));
    }

    @Test
    void 필수값이_없거나_잘못된_요청은_400을_반환한다()
            throws Exception {
        Long userId = createUser(406L);
        CustomUserDetails principal = principal(userId);

        mockMvc.perform(put(INTEREST_URL)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interests": null
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(INTEREST_URL)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interests": [
                                    {
                                      "type": null,
                                      "code": "일자리"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(INTEREST_URL)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interests": [
                                    {
                                      "type": "CATEGORY",
                                      "code": "   "
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private Long createUser(Long kakaoId) {
        User user = userRepository.saveAndFlush(
                User.builder()
                        .kakaoId(kakaoId)
                        .role(Role.USER)
                        .build()
        );

        return user.getId();
    }

    private CustomUserDetails principal(Long userId) {
        return new CustomUserDetails(
                userId,
                Role.USER,
                "interest-test-session"
        );
    }
}