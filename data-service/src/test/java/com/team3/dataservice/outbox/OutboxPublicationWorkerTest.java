package com.team3.dataservice.outbox;

import com.team3.dataservice.outbox.publisher.CollectionEventPublisher;
import com.team3.dataservice.outbox.publisher.PublicationException;
import com.team3.dataservice.outbox.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublicationWorkerTest {
    @Test
    void databaseFailureAfterSqsAcknowledgementLeavesLeaseForRecoveryRatherThanMarkingSendFailure() {
        var store = mock(OutboxPublicationStore.class);
        var publisher = mock(CollectionEventPublisher.class);
        var worker = new OutboxPublicationWorker(store, publisher, CollectionNotificationPublisherConfigTest.properties());
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
        var publisher = mock(CollectionEventPublisher.class);
        var worker = new OutboxPublicationWorker(store, publisher, CollectionNotificationPublisherConfigTest.properties());
        var first = new OutboxPublication(1L, UUID.randomUUID(), UUID.randomUUID(), "{}");
        var second = new OutboxPublication(2L, UUID.randomUUID(), UUID.randomUUID(), "{}");
        when(store.claimNext()).thenReturn(Optional.of(first), Optional.of(second));
        doThrow(PublicationException.configuration("SQS_CONFIGURATION_ERROR")).when(publisher).publish(first);
        worker.runOnce();
        verify(store).deferred(first, "SQS_CONFIGURATION_ERROR");
        verify(store, never()).failed(any(), anyString(), anyBoolean());
        verify(store, times(1)).claimNext();
        verify(publisher, never()).publish(second);
    }

    @Test
    void permanentMessageErrorDoesNotBlockTheNextMessage() {
        var store = mock(OutboxPublicationStore.class);
        var publisher = mock(CollectionEventPublisher.class);
        var worker = new OutboxPublicationWorker(store, publisher, CollectionNotificationPublisherConfigTest.properties());
        var first = new OutboxPublication(1L, UUID.randomUUID(), UUID.randomUUID(), "{}");
        var second = new OutboxPublication(2L, UUID.randomUUID(), UUID.randomUUID(), "{}");
        when(store.claimNext()).thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        doThrow(new PublicationException("INVALID_OUTBOX_ENVELOPE", false)).when(publisher).publish(first);
        worker.runOnce();
        verify(store).failed(first, "INVALID_OUTBOX_ENVELOPE", false);
        verify(publisher).publish(second);
        verify(store).published(second);
    }
}
