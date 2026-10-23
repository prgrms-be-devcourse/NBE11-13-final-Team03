package com.team3.backend.outbox;

import com.team3.backend.outbox.publisher.NotificationMessagePublisher;
import com.team3.backend.outbox.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublicationWorkerTest {
    @Test
    void databaseFailureAfterHttpAcknowledgementLeavesLeaseForRecoveryRatherThanMarkingSendFailure() {
        var store = mock(OutboxPublicationStore.class);
        var publisher = mock(NotificationMessagePublisher.class);
        var worker = new OutboxPublicationWorker(store, publisher, HttpNotificationPublisherTest.properties(8081));
        var publication = new OutboxPublication(1L, UUID.randomUUID(), UUID.randomUUID(), "{}");
        when(store.claimNext()).thenReturn(Optional.of(publication));
        var failure = new DataAccessResourceFailureException("database unavailable after remote commit");
        when(store.published(publication)).thenThrow(failure);
        assertThatThrownBy(worker::runOnce).isSameAs(failure);
        verify(publisher).publish(publication);
        verify(store, never()).failed(any(), anyString(), anyBoolean());
    }
}
