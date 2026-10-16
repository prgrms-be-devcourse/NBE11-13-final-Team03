package com.team3.backend.collection.service;

import com.team3.backend.collection.client.YouthPolicyClient;
import com.team3.backend.collection.dto.external.YouthPolicyPage;
import com.team3.backend.collection.exception.YouthPolicyClientException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class YouthPolicyCollectionServiceTests {
    private final YouthPolicyClient client = mock(YouthPolicyClient.class);
    private final YouthPolicyCollectionService service = new YouthPolicyCollectionService(client);

    private YouthPolicyPage page(Integer totalCount) {
        return new YouthPolicyPage(totalCount, List.of(JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void continuesAfterFailureAndReturnsItsDetails() {
        when(client.fetchPage(1, 1)).thenReturn(page(3));
        when(client.fetchPage(2, 1)).thenThrow(new YouthPolicyClientException("HTTP_503", true));
        when(client.fetchPage(3, 1)).thenReturn(page(3));

        var result = service.collect(1, 10);

        assertTrue(result.completed());
        assertEquals(2, result.records().size());
        assertEquals(1, result.pageFailures().size());
        var failure = result.pageFailures().getFirst();
        assertEquals(2, failure.pageNumber());
        assertEquals("HTTP_503", failure.errorCode());
        assertTrue(failure.retryable());
        verify(client).fetchPage(3, 1);
        verify(client, never()).fetchPage(4, 1);
    }

    @Test
    void continuesWhenFirstPageFailsAndTotalCountIsUnknown() {
        when(client.fetchPage(1, 2)).thenThrow(new YouthPolicyClientException("HTTP_400", false));
        when(client.fetchPage(2, 2)).thenReturn(page(null));
        when(client.fetchPage(3, 2)).thenReturn(new YouthPolicyPage(null, List.of()));

        var result = service.collect(2, 10);

        assertTrue(result.completed());
        assertEquals(1, result.records().size());
        assertFalse(result.pageFailures().getFirst().retryable());
        verify(client).fetchPage(3, 2);
    }

    @Test
    void stopsAtLimitInsteadOfLoopingForeverOnFailures() {
        when(client.fetchPage(anyInt(), eq(1)))
                .thenThrow(new YouthPolicyClientException("CONNECTION_ERROR", true));

        var result = service.collect(1, 3);

        assertFalse(result.completed());
        assertEquals(List.of(1, 2, 3), result.pageFailures().stream()
                .map(failure -> failure.pageNumber()).toList());
        verify(client, never()).fetchPage(4, 1);
    }

    @Test
    void doesNotTreatConfigurationErrorsAsPageFailures() {
        when(client.fetchPage(1, 1)).thenThrow(new IllegalStateException("Missing key"));
        assertThrows(IllegalStateException.class, () -> service.collect(1, 3));
        verify(client, never()).fetchPage(2, 1);
        assertThrows(IllegalArgumentException.class, () -> service.collect(0, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_401", "HTTP_403"})
    void stopsOnAuthenticationFailure(String errorCode) {
        when(client.fetchPage(1, 1)).thenThrow(new YouthPolicyClientException(errorCode, false));
        var result = service.collect(1, 1000);
        assertFalse(result.completed());
        assertEquals(1, result.pageFailures().size());
        verify(client, never()).fetchPage(2, 1);
    }

    @Test
    void interruptionStopsBeforeAnExternalRequest() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> service.collect(1, 1));
            verifyNoInteractions(client);
        } finally {
            Thread.interrupted();
        }
    }
}
