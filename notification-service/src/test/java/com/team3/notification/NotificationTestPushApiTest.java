package com.team3.notification;

import com.team3.notification.api.NotificationTestPushController;
import com.team3.notification.service.NotificationTestPushService;
import com.team3.notification.service.NotificationUserApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"NOTIFICATION_TEST_PUSH_ENABLED=true", "NOTIFICATION_TEST_PUSH_API_KEY=test-push-key",
        "NOTIFICATION_TEST_PUSH_ALLOWED_USER_IDS=1", "NOTIFICATION_USER_API_ENABLED=true",
        "NOTIFICATION_RECEIVER_ENABLED=true", "NOTIFICATION_INTERNAL_API_KEY=normal-internal-key",
        "app.scheduling.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NotificationTestPushApiTest {
    private static final String PATH = NotificationTestPushController.PATH;
    private static final String BODY = "{\"userId\":1,\"notificationId\":7,\"endpointId\":32}";
    @Autowired MockMvc mvc;
    @MockitoBean NotificationTestPushService service;

    @Test
    @WithMockUser(authorities = "NOTIFICATION_TEST_PUSH")
    void browserAuthenticationAndNormalServiceKeyCannotReplaceDedicatedKey() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        for (String key : new String[]{"", "wrong", "normal-internal-key"}) {
            mvc.perform(post(PATH).header("X-INTERNAL-KEY", key).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-push-key", "test-push-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void dedicatedKeyOnlyOpensPostTestPushAndReturnsReceipt() throws Exception {
        when(service.send(1, 7, 32)).thenReturn(new NotificationTestPushService.Result(
                UUID.randomUUID(), 7, 32, "FCM_ACCEPTED", "projects/test/messages/receipt"));
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-push-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FCM_ACCEPTED"))
                .andExpect(jsonPath("$.fcmMessageId").value("projects/test/messages/receipt"));
        mvc.perform(get(PATH).header("X-INTERNAL-KEY", "test-push-key")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/users/1/notifications").header("X-INTERNAL-KEY", "test-push-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/notification-messages").header("X-INTERNAL-KEY", "test-push-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/test-push").header("X-INTERNAL-KEY", "test-push-key"))
                .andExpect(status().isForbidden());
        verify(service).send(1, 7, 32);
    }

    @Test
    void requiresPositiveIdsAndReturnsSafeFailureCodes() throws Exception {
        for (String body : new String[]{"{}", "{\"userId\":0,\"notificationId\":7,\"endpointId\":32}",
                "{\"userId\":1,\"notificationId\":-1,\"endpointId\":32}",
                "{\"userId\":1,\"notificationId\":7,\"endpointId\":0}", "invalid"}) {
            mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-push-key")
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        when(service.send(1, 7, 32)).thenThrow(new NotificationUserApiException(409, "FCM_UNREGISTERED"));
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-push-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(content().json("{\"code\":\"FCM_UNREGISTERED\"}"));
        doThrow(new IllegalStateException("private-token-or-database-error")).when(service).send(1, 7, 32);
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-push-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"code\":\"NOTIFICATION_UNAVAILABLE\"}"));
    }
}
