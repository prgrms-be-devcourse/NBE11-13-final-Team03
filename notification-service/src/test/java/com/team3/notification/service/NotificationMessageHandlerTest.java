package com.team3.notification.service;

import com.team3.notification.domain.entity.ReceptionStatus;
import com.team3.notification.fixture.NotificationMessages;
import com.team3.notification.message.*;
import jakarta.validation.Validation;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;
import java.sql.SQLException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationMessageHandlerTest {
    @Test
    void recoversOnlyKnownUniquenessConflictsThroughSeparateTransactionBean() {
        var transaction = mock(NotificationReceiveTransaction.class);
        var message = NotificationMessages.message(Map.of());
        var result = new NotificationReceptionResult(message.messageId(), ReceptionStatus.CREATED, 1L, true);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var mapper = JsonMapper.builder().build();
            var handler = new NotificationMessageHandler(new NotificationMessageDecoder(mapper),
                    new NotificationMessageValidator(validation.getValidator()),
                    new NotificationMessageFingerprint(mapper), transaction);
            for (String constraint : new String[]{"uk_consumed_messages_message_id", "uk_notifications_user_source_event"}) {
                reset(transaction);
                when(transaction.receive(eq(message), anyString()))
                        .thenThrow(failure("23505", constraint)).thenReturn(result);
                assertThat(handler.handle(message)).isEqualTo(result);
                verify(transaction, times(2)).receive(eq(message), anyString());
            }
            for (var failure : new DataIntegrityViolationException[]{
                    failure("23505", "notifications_pkey"), failure("23503", "fk_consumed_notification"),
                    failure("23505", null),
                    failure("23514", "reject_test_consume"), new DataIntegrityViolationException("unknown")}) {
                reset(transaction);
                when(transaction.receive(eq(message), anyString())).thenThrow(failure);
                assertThatThrownBy(() -> handler.handle(message)).isSameAs(failure);
                verify(transaction).receive(eq(message), anyString());
            }
            reset(transaction);
            var duplicate = failure("23505", "uk_consumed_messages_message_id");
            when(transaction.receive(eq(message), anyString())).thenThrow(duplicate);
            assertThatThrownBy(() -> handler.handle(message)).isSameAs(duplicate);
            verify(transaction, times(2)).receive(eq(message), anyString());
        }
    }

    private DataIntegrityViolationException failure(String state, String constraint) {
        return new DataIntegrityViolationException("test integrity failure",
                new ConstraintViolationException("test", new SQLException("test", state), "insert", constraint));
    }
}
