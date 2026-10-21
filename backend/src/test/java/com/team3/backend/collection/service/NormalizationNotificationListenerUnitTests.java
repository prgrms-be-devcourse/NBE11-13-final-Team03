package com.team3.backend.collection.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NormalizationNotificationListenerUnitTests {
    private final DataSourceProperties properties = new DataSourceProperties();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NormalizationNotificationListener listener = spy(
            new NormalizationNotificationListener(properties, events));

    @Test
    void publishesWakeUpSignalWithoutProcessingRequests() {
        listener.signalPendingRequests();
        verify(events).publishEvent(new NormalizationRequestsAvailable());
        verify(listener, never()).notificationDataSource();
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
        properties.setUrl("jdbc:h2:mem:notifications");
        listener.start();
        verify(listener, never()).notificationDataSource();
        verifyNoInteractions(events);
        listener.stop();
    }

    @Test
    void createsUnpooledPostgresConnectionsFromApplicationProperties() {
        properties.setUrl("jdbc:postgresql://localhost:5432/yon?sslmode=require");
        properties.setUsername("listener");
        properties.setPassword("test-password");
        var source = assertInstanceOf(PGSimpleDataSource.class, listener.notificationDataSource());
        assertThat(source.getUrl()).startsWith("jdbc:postgresql://localhost:5432/yon?")
                .contains("sslmode=require");
        assertEquals(properties.getUsername(), source.getUser());
        assertEquals(properties.getPassword(), source.getPassword());
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void workerUnlistensBeforeClosingEvenWhenUnlistenFails(boolean failUnlisten) throws Exception {
        properties.setUrl("jdbc:postgresql://localhost:5432/yon");
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var postgres = mock(PGConnection.class);
        doReturn(source).when(listener).notificationDataSource();
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.unwrap(PGConnection.class)).thenReturn(postgres);
        when(postgres.getNotifications(1_000)).thenAnswer(invocation -> {
            Thread.sleep(50);
            return new PGNotification[0];
        });
        if (failUnlisten) {
            when(statement.execute("UNLISTEN " + NormalizationNotificationListener.CHANNEL))
                    .thenThrow(new SQLException("connection lost"));
        }
        try {
            listener.start();
            verify(events, timeout(2_000)).publishEvent(any(NormalizationRequestsAvailable.class));
            listener.start(); // 같은 인스턴스의 중복 구독을 방지한다.
        } finally {
            listener.stop();
        }
        var order = inOrder(statement, connection);
        order.verify(statement).execute("LISTEN " + NormalizationNotificationListener.CHANNEL);
        order.verify(statement).execute("UNLISTEN " + NormalizationNotificationListener.CHANNEL);
        order.verify(connection).close();
        verify(source, times(1)).getConnection();
    }

    @Test
    void listenerIsDisabledUntilConsumerIsReady() {
        context().run(context -> assertThat(context).doesNotHaveBean(NormalizationNotificationListener.class));
    }

    @ParameterizedTest
    @CsvSource({"true,api,true", "true,all,true", "true,tracking,false", "false,api,false"})
    void requiresExplicitEnablementAndConsumerRole(boolean enabled, String role, boolean expected) {
        context().withPropertyValues("collection.normalization.enabled=" + enabled, "app.role=" + role)
                .run(context -> assertEquals(expected, context.containsBean("normalizationNotificationListener")));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(NormalizationNotificationListener.class)
                .withBean(DataSourceProperties.class, () -> properties);
    }
}
