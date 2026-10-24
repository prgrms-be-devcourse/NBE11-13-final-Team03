package com.team3.backend.user.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.client.NotificationUserClient;
import com.team3.backend.client.dto.NotificationUserRequests;
import com.team3.backend.user.domain.entity.Role;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"NOTIFICATION_FACADE_ENABLED=true", "NOTIFICATION_BASE_URL=http://127.0.0.1:1",
        "NOTIFICATION_INTERNAL_API_KEY=facade-test-key", "NOTIFICATION_PUBLISHER_ENABLED=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UserNotificationFacadeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean NotificationUserClient client;
    private final CustomUserDetails principal = new CustomUserDetails(7L, Role.USER, "facade-test");

    @Test
    void authenticatesBeforeRelayAndKeepsExistingCsrfProtection() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/notifications/1/read").with(user(principal))).andExpect(status().isForbidden());
        verifyNoInteractions(client);
    }

    @Test
    void neverTrustsBrowserUserIdOrServiceCredentials() throws Exception {
        when(client.list(7, null, 20)).thenReturn(response(200, "{\"items\":[],\"nextCursor\":null,\"hasNext\":false}"));
        mvc.perform(get("/api/notifications").with(user(principal)).param("userId", "999")
                .header("X-INTERNAL-KEY", "browser-supplied-key").header("X-User-ID", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        verify(client).list(7, null, 20);
    }

    @Test
    void relaysAllMutationsAndSettingsWithoutLocalPersistence() throws Exception {
        when(client.read(7, 9)).thenReturn(response(204, ""));
        when(client.readAll(7)).thenReturn(response(204, ""));
        when(client.click(7, 9)).thenReturn(response(204, ""));
        when(client.settings(7)).thenReturn(response(200, "{\"inAppEnabled\":true,\"pushEnabled\":false}"));
        when(client.settings(7, new NotificationUserRequests.Settings(false, false)))
                .thenReturn(response(200, "{\"inAppEnabled\":false,\"pushEnabled\":false}"));
        when(client.endpoint(7, new NotificationUserRequests.Endpoint("test-token")))
                .thenReturn(response(200, "{\"id\":11,\"active\":true}"));
        when(client.removeEndpoint(7, 11)).thenReturn(response(204, ""));
        mvc.perform(patch("/api/notifications/9/read").with(user(principal)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(patch("/api/notifications/read-all").with(user(principal)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/notifications/9/click").with(user(principal)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me/notification-settings").with(user(principal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pushEnabled").value(false));
        mvc.perform(put("/api/users/me/notification-settings").with(user(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"inAppEnabled\":false,\"pushEnabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/users/me/notification-endpoints").with(user(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"test-token\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(11));
        mvc.perform(delete("/api/users/me/notification-endpoints/11").with(user(principal)).with(csrf()))
                .andExpect(status().isNoContent());
        verify(client).endpoint(7, new NotificationUserRequests.Endpoint("test-token"));
    }

    @Test
    void unavailableCountIsNotZeroAndOtherFailuresAre503() throws Exception {
        when(client.unread(7)).thenThrow(new IllegalStateException("remote down"));
        when(client.settings(7)).thenReturn(response(401, "private diagnostics"));
        mvc.perform(get("/api/notifications/unread-count").with(user(principal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unreadCount").doesNotExist());
        mvc.perform(get("/api/users/me/notification-settings").with(user(principal)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NOTIFICATION_UNAVAILABLE"));
        when(client.read(7, 99)).thenReturn(response(404, "private diagnostics"));
        mvc.perform(patch("/api/notifications/99/read").with(user(principal)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void invalidPaginationAndMissingSettingsAre400Not500() throws Exception {
        mvc.perform(get("/api/notifications").with(user(principal)).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/notifications").with(user(principal)).param("beforeId", "0")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/me/notification-settings").with(user(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    private Response response(int status, String body) {
        return Response.builder().status(status).reason("test").headers(Map.of())
                .request(Request.create(Request.HttpMethod.GET, "http://test", Map.of(), null, StandardCharsets.UTF_8, null))
                .body(body, StandardCharsets.UTF_8).build();
    }
}
