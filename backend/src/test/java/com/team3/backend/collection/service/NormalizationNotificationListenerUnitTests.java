package com.team3.backend.collection.service;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class NormalizationNotificationListenerUnitTests {
    private final DataSource dataSource = mock(DataSource.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NormalizationNotificationListener listener =
            new NormalizationNotificationListener(dataSource, events);

    @Test
    void publishesWakeUpSignalWithoutProcessingRequests() {
        listener.signalPendingRequests();
        verify(events).publishEvent(new NormalizationRequestsAvailable());
        verifyNoInteractions(dataSource);
    }

    @Test
    void consumerFailureDoesNotStopSubsequentSignals() {
        doThrow(new IllegalStateException("consumer unavailable")).doNothing()
                .when(events).publishEvent(any(NormalizationRequestsAvailable.class));
        assertDoesNotThrow(listener::signalPendingRequests);
        assertDoesNotThrow(listener::signalPendingRequests);
        verify(events, times(2)).publishEvent(any(NormalizationRequestsAvailable.class));
    }

    @Test
    void skipsUnsupportedDatabases() throws Exception {
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        listener.start();
        verify(connection).close();
        verifyNoInteractions(events);
        listener.stop();
    }
}
