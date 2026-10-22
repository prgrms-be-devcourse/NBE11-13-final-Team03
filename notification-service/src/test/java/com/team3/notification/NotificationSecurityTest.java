package com.team3.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/internal/notifications")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void browserAuthenticationDoesNotOpenInternalEndpoints() throws Exception {
        mockMvc.perform(get("/api/internal/notifications")).andExpect(status().isForbidden());
    }
}
