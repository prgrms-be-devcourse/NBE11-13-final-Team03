package com.team3.notification.service;

import com.team3.notification.domain.entity.ReceptionStatus;
import com.team3.notification.message.*;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSessionEndHandlerTest {
    @Test
    void validatesControlBeforeTransactionAndDoesNotHideUnknownIntegrityFailures() throws Exception {
        var mapper = JsonMapper.builder().build();
        var decoder = new NotificationMessageDecoder(mapper);
        var message = decoder.decodeSessionEnd(new ClassPathResource("contracts/user-session-ended-v1.json")
                .getContentAsString(StandardCharsets.UTF_8));
        var transaction = mock(NotificationSessionEndTransaction.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var handler = new UserSessionEndHandler(factory.getValidator(), new NotificationMessageFingerprint(mapper), transaction);
            for (var invalid : new UserSessionEndedMessage[]{
                    new UserSessionEndedMessage(1, message.messageId(), "USER_SESSION_ENDED", message.sourceEventId(),
                            message.correlationId(), message.causationId(), 0L, message.sessionId(), message.occurredAt()),
                    new UserSessionEndedMessage(1, message.messageId(), "USER_SESSION_ENDED", message.sourceEventId(),
                            message.correlationId(), message.causationId(), 7L, " ", message.occurredAt()),
                    new UserSessionEndedMessage(1, message.messageId(), "USER_SESSION_ENDED", message.sourceEventId(),
                            message.correlationId(), message.causationId(), 7L, "x".repeat(37), message.occurredAt())}) {
                assertThatThrownBy(() -> handler.handle(invalid)).isInstanceOf(ConstraintViolationException.class);
            }
            verifyNoInteractions(transaction);
            var receipt = new NotificationReceptionResult(message.messageId(), ReceptionStatus.SESSION_ENDED, null, false);
            when(transaction.receive(eq(message), anyString())).thenReturn(receipt);
            assertThat(handler.handle(message)).isEqualTo(receipt);
            reset(transaction);
            var failure = new DataIntegrityViolationException("unrelated storage constraint");
            when(transaction.receive(eq(message), anyString())).thenThrow(failure);
            assertThatThrownBy(() -> handler.handle(message)).isSameAs(failure);
            verify(transaction).receive(eq(message), anyString());
        }
    }
}
