package com.team3.backend.collection.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.scheduling.enabled=false")
@AutoConfigureMockMvc
class CollectionSecurityTests {
    @Autowired private MockMvc mvc;

    @Test
    @WithMockUser(authorities = "ADMIN")
    void adminCanReachCollectionServices() throws Exception {
        mvc.perform(get("/api/admin/collection/syncs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLLECTION_004"));
        mvc.perform(get("/api/admin/collection/raw-records/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "USER")
    void memberCannotReachCollectionServices() throws Exception {
        mvc.perform(get("/api/admin/collection/syncs/1"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/collection/raw-records/1"))
                .andExpect(status().isForbidden());
    }
}
