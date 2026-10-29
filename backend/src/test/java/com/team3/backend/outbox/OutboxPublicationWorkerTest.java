package com.team3.backend.outbox;

import com.team3.backend.outbox.publisher.NotificationMessagePublisher;
import com.team3.backend.outbox.publisher.NotificationPublicationException;
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

    @Test
    void configurationErrorDefersWithoutSpendingAnAttemptAndStopsTheBatch() {
        var store = mock(OutboxPublicationStore.class);
        var publisher = mock(NotificationMessagePublisher.class);
        var worker = new OutboxPublicationWorker(store, publisher, HttpNotificationPublisherTest.properties(8081));
        var first = new OutboxPublication(1L, UUID.randomUUID(), UUID.randomUUID(), "{}");
        var second = new OutboxPublication(2L, UUID.randomUUID(), UUID.randomUUID(), "{}");
        when(store.claimNext()).thenReturn(Optional.of(first), Optional.of(second));
        doThrow(NotificationPublicationException.configuration("HTTP_401")).when(publisher).publish(first);
        worker.runOnce();
        verify(store).deferred(first, "HTTP_401");
        verify(store, never()).failed(any(), anyString(), anyBoolean());
        verify(store, times(1)).claimNext();
        verify(publisher, never()).publish(second);
    }
}
