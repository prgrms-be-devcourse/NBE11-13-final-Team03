package com.team3.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NotificationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnonymousRequestsWhileNoEndpointIsOpenYet() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/users/7/notifications").header("X-INTERNAL-KEY", "any-key"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/internal/notifications")).andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/notification-messages")
                .header("X-INTERNAL-KEY", "any-key")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void browserAuthenticationDoesNotOpenInternalEndpoints() throws Exception {
        mockMvc.perform(get("/api/internal/notifications")).andExpect(status().isForbidden());
    }
}
