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

class UserWithdrawalHandlerTest {
    @Test
    void validatesControlBeforeTransactionAndDoesNotHideUnknownIntegrityFailures() throws Exception {
        var mapper = JsonMapper.builder().build();
        var decoder = new NotificationMessageDecoder(mapper);
        var message = decoder.decodeWithdrawal(new ClassPathResource("contracts/user-withdrawn-v1.json")
                .getContentAsString(StandardCharsets.UTF_8));
        var transaction = mock(NotificationWithdrawalTransaction.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var handler = new UserWithdrawalHandler(factory.getValidator(), new NotificationMessageFingerprint(mapper), transaction);
            var invalid = new UserWithdrawnMessage(1, message.messageId(), "USER_WITHDRAWN", message.sourceEventId(),
                    message.correlationId(), message.causationId(), 0L, message.occurredAt());
            assertThatThrownBy(() -> handler.handle(invalid)).isInstanceOf(ConstraintViolationException.class);
            verifyNoInteractions(transaction);
            var receipt = new NotificationReceptionResult(message.messageId(), ReceptionStatus.WITHDRAWN, null, false);
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
