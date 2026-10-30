package com.team3.notification;

import com.team3.notification.api.NotificationUserDtos.*;
import com.team3.notification.config.NotificationUserApiProperties;
import com.team3.notification.service.NotificationUserApiException;
import com.team3.notification.service.NotificationUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import com.team3.notification.config.NotificationSecurityConfig;
import com.team3.notification.api.NotificationUserController;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NotificationUserController.class, properties = {"NOTIFICATION_USER_API_ENABLED=true", "NOTIFICATION_RECEIVER_ENABLED=false",
        "NOTIFICATION_INTERNAL_API_KEY=test-user-api-key"})
@ActiveProfiles("test")
@Import({NotificationSecurityConfig.class})
class NotificationUserApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean NotificationUserService service;
    private static final String ROOT = "/internal/users/7";

    @Test
    void requiresExactlyOneDedicatedKeyAndNeverOpensPublicOrMessageEndpoints() throws Exception {
        mvc.perform(get(ROOT + "/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/notifications").header("X-INTERNAL-KEY", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/notifications").header("X-INTERNAL-KEY", "test-user-api-key", "test-user-api-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/notification-messages").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void routesAllNineOperationsUsingOnlyInternalUserId() throws Exception {
        when(service.unreadCount(7)).thenReturn(new UnreadCount(true, 3L));
        mvc.perform(get(ROOT + "/notifications").header("X-INTERNAL-KEY", "test-user-api-key")).andExpect(status().isOk());
        mvc.perform(get(ROOT + "/notifications/unread-count").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(3));
        mvc.perform(patch(ROOT + "/notifications/9/read").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isNoContent());
        mvc.perform(patch(ROOT + "/notifications/read-all").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isNoContent());
        mvc.perform(post(ROOT + "/notifications/9/click").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isNoContent());
        mvc.perform(get(ROOT + "/notification-settings").header("X-INTERNAL-KEY", "test-user-api-key")).andExpect(status().isOk());
        mvc.perform(put(ROOT + "/notification-settings").header("X-INTERNAL-KEY", "test-user-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"inAppEnabled\":false,\"pushEnabled\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post(ROOT + "/notification-endpoints").header("X-INTERNAL-KEY", "test-user-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"token\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(ROOT + "/notification-endpoints").header("X-INTERNAL-KEY", "test-user-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"token\",\"sessionId\":\"session-1\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete(ROOT + "/notification-endpoints/9").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isNoContent());
        verify(service).list(7, null, 20);
        verify(service).read(7, 9);
        verify(service).readAll(7);
        verify(service).click(7, 9);
        verify(service).updateSettings(7, new SettingsRequest(false, true));
        verify(service).registerEndpoint(7, new EndpointRequest("token"));
        verify(service).registerEndpoint(7, new EndpointRequest("token", "session-1"));
        verify(service).deactivateEndpoint(7, 9);
    }

    @Test
    void rejectsInvalidInputsAndDoesNotExposeTokensOrDatabaseErrors() throws Exception {
        mvc.perform(get("/internal/users/0/notifications").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(ROOT + "/notification-settings").header("X-INTERNAL-KEY", "test-user-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"inAppEnabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/notification-endpoints").header("X-INTERNAL-KEY", "test-user-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"bad token\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":\"INVALID_NOTIFICATION_REQUEST\"}"));
        mvc.perform(post(ROOT + "/notification-endpoints").header("X-INTERNAL-KEY", "test-user-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"token\",\"sessionId\":\"" + "s".repeat(37) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":\"INVALID_NOTIFICATION_REQUEST\"}"));
        doThrow(new NotificationUserApiException(404, "NOTIFICATION_NOT_FOUND")).when(service).read(7, 9);
        mvc.perform(patch(ROOT + "/notifications/9/read").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isNotFound());
        when(service.settings(7)).thenThrow(new IllegalStateException("private database error"));
        mvc.perform(get(ROOT + "/notification-settings").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"code\":\"NOTIFICATION_UNAVAILABLE\"}"));
    }

    @Test
    void enabledUserApiRequiresSecretAndRedactsIt() {
        assertThatThrownBy(() -> new NotificationUserApiProperties(true, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new NotificationUserApiProperties(true, "secret").toString()).doesNotContain("secret");
    }

    @Test
    void tombstoneRejectionIsAnExplicit410WithoutPrivateDetails() throws Exception {
        when(service.settings(7)).thenThrow(new NotificationUserApiException(410, "USER_WITHDRAWN"));
        mvc.perform(get(ROOT + "/notification-settings").header("X-INTERNAL-KEY", "test-user-api-key"))
                .andExpect(status().isGone()).andExpect(content().json("{\"code\":\"USER_WITHDRAWN\"}"));
    }
}
