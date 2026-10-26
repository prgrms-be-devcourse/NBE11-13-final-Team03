package com.team3.notification;

import com.team3.notification.config.NotificationReceiverProperties;
import com.team3.notification.domain.entity.ReceptionStatus;
import com.team3.notification.fixture.NotificationMessages;
import com.team3.notification.message.NotificationRequestedMessage;
import com.team3.notification.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"NOTIFICATION_RECEIVER_ENABLED=true", "NOTIFICATION_INTERNAL_API_KEY=test-notification-key"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NotificationMessageApiTest {
    private static final String PATH = "/internal/notification-messages";
    @Autowired MockMvc mvc;
    @MockitoBean NotificationMessageHandler handler;

    @Test
    void withdrawalUsesSameAuthenticatedEndpointAndAcknowledgesOnlyAfterHandlerReturns() throws Exception {
        String json = new ClassPathResource("contracts/user-withdrawn-v1.json").getContentAsString(StandardCharsets.UTF_8);
        var id = java.util.UUID.fromString("11111111-1111-4111-8111-111111111112");
        when(handler.handle(json)).thenReturn(new NotificationReceptionResult(id, ReceptionStatus.WITHDRAWN, null, false));
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-notification-key")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        verify(handler).handle(json);
    }

    @Test
    void acceptsOnlyDedicatedKeyAndProducesSharedReceiptContract() throws Exception {
        var message = NotificationMessages.message(Map.of());
        when(handler.handle(any(NotificationRequestedMessage.class))).thenReturn(new NotificationReceptionResult(message.messageId(), ReceptionStatus.CREATED, 1L, false));
        String expected = new ClassPathResource("contracts/notification-receipt-v1.json")
                .getContentAsString(StandardCharsets.UTF_8);
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-notification-key")
                        .contentType(MediaType.APPLICATION_JSON).content(NotificationMessages.json(Map.of())))
                .andExpect(status().isOk()).andExpect(content().json(expected));
        verify(handler).handle(any(NotificationRequestedMessage.class));
    }

    @Test
    @WithMockUser(authorities = "NOTIFICATION_PUBLISH")
    void browserAuthenticationDoesNotReplaceServiceKeyAndOtherRoutesRemainDenied() throws Exception {
        for (String key : new String[]{"", "wrong", "test-internal-api-key"}) {
            mvc.perform(post(PATH).header("X-INTERNAL-KEY", key).contentType(MediaType.APPLICATION_JSON)
                            .content(NotificationMessages.json(Map.of())))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-notification-key", "test-notification-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("X-INTERNAL-KEY", "test-notification-key")).andExpect(status().isForbidden());
        mvc.perform(get("/api/notifications").header("X-INTERNAL-KEY", "test-notification-key")).andExpect(status().isForbidden());
        verifyNoInteractions(handler);
    }

    @Test
    void errorsHaveSpecificStatusesAndNeverEchoCredentialsOrPayload() throws Exception {
        doThrow(new IllegalArgumentException("private payload")).when(handler).handle(any(NotificationRequestedMessage.class));
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-notification-key")
                        .contentType(MediaType.APPLICATION_JSON).content(NotificationMessages.json(Map.of())))
                .andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":\"INVALID_NOTIFICATION_MESSAGE\"}"));
        doThrow(new MessageIdentityConflictException()).when(handler).handle(any(NotificationRequestedMessage.class));
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-notification-key")
                        .contentType(MediaType.APPLICATION_JSON).content(NotificationMessages.json(Map.of())))
                .andExpect(status().isConflict());
        doThrow(new DataAccessResourceFailureException("private database details")).when(handler).handle(any(NotificationRequestedMessage.class));
        mvc.perform(post(PATH).header("X-INTERNAL-KEY", "test-notification-key")
                        .contentType(MediaType.APPLICATION_JSON).content(NotificationMessages.json(Map.of())))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"code\":\"NOTIFICATION_UNAVAILABLE\"}"));
    }

    @Test
    void enablingReceiverWithoutDedicatedKeyFailsConfiguration() {
        assertThatThrownBy(() -> new NotificationReceiverProperties(true, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new NotificationReceiverProperties(true, "test-notification-key").toString())
                .doesNotContain("test-notification-key");
    }
}
