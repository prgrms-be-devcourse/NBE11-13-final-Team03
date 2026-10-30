package com.team3.notification.service;

import com.team3.notification.domain.entity.ReceptionStatus;
import com.team3.notification.fixture.NotificationMessages;
import com.team3.notification.message.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationMessageHandlerTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final NotificationReceiveTransaction transaction = mock(NotificationReceiveTransaction.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final NotificationMessageHandler handler = new NotificationMessageHandler(
            new NotificationMessageDecoder(mapper), new NotificationMessageValidator(VALIDATORS.getValidator()),
            new NotificationMessageFingerprint(mapper), transaction, mock(UserWithdrawalHandler.class),
            mock(UserSessionEndHandler.class));
    private final NotificationRequestedMessage message = NotificationMessages.message(Map.of());

    @AfterAll
    static void closeValidators() { VALIDATORS.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"uk_consumed_messages_message_id", "uk_notifications_user_source_event"})
    void knownDuplicateIsRetriedOnceAndReturnsTheCommittedReceipt(String constraint) {
        var result = new NotificationReceptionResult(message.messageId(), ReceptionStatus.CREATED, 1L, true);
        when(transaction.receive(eq(message), anyString()))
                .thenThrow(failure("23505", constraint)).thenReturn(result);

        assertThat(handler.handle(message)).isEqualTo(result);
        verify(transaction, times(2)).receive(eq(message), anyString());
    }

    @ParameterizedTest
    @MethodSource("unrecoverableFailures")
    void otherDatabaseFailuresPropagateWithoutRetry(DataIntegrityViolationException failure) {
        when(transaction.receive(eq(message), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> handler.handle(message)).isSameAs(failure);
        verify(transaction).receive(eq(message), anyString());
    }

    static Stream<DataIntegrityViolationException> unrecoverableFailures() {
        return Stream.of(failure("23505", "notifications_pkey"), failure("23503", "fk_consumed_notification"),
                failure("23505", null), failure("23514", "reject_test_consume"),
                new DataIntegrityViolationException("unknown"));
    }

    @Test
    void repeatedUniquenessConflictIsNotConvertedIntoASuccess() {
        var duplicate = failure("23505", "uk_consumed_messages_message_id");
        when(transaction.receive(eq(message), anyString())).thenThrow(duplicate);

        assertThatThrownBy(() -> handler.handle(message)).isSameAs(duplicate);
        verify(transaction, times(2)).receive(eq(message), anyString());
    }

    @Test
    void invalidMessageCannotReachPersistenceThroughThePublicJsonEntryPoint() {
        var invalid = NotificationMessages.json(Map.of("link", "https://external.example"));

        assertThatThrownBy(() -> handler.handle(invalid)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transaction);
    }

    private static DataIntegrityViolationException failure(String state, String constraint) {
        return new DataIntegrityViolationException("test integrity failure",
                new ConstraintViolationException("test", new SQLException("test", state), "insert", constraint));
    }
}
