package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.client.YouthPolicyClient;
import com.team3.dataservice.collection.dto.external.YouthPolicyPage;
import com.team3.dataservice.collection.exception.YouthPolicyClientException;
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

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_503", "CONNECTION_ERROR", "API_ERROR", "HTTP_429"})
    void stopsAfterThreeFirstPageAttempts(String errorCode) {
        when(client.fetchPage(anyInt(), eq(1)))
                .thenThrow(new YouthPolicyClientException(errorCode, true));

        var result = service.collect(1, 1000);

        assertFalse(result.completed());
        assertTrue(result.records().isEmpty());
        assertEquals(List.of(1), result.pageFailures().stream()
                .map(failure -> failure.pageNumber()).toList());
        verify(client, times(3)).fetchPage(1, 1);
        verifyNoMoreInteractions(client);
    }

    @Test
    void firstPageRecoveryContinuesWithoutRecordingTransientFailures() {
        when(client.fetchPage(1, 1))
                .thenThrow(new YouthPolicyClientException("HTTP_503", true))
                .thenThrow(new YouthPolicyClientException("API_ERROR", true))
                .thenReturn(page(2));
        when(client.fetchPage(2, 1)).thenReturn(page(2));
        var result = service.collect(1, 1000);
        assertTrue(result.completed());
        assertEquals(2, result.records().size());
        assertTrue(result.pageFailures().isEmpty());
        verify(client, times(3)).fetchPage(1, 1);
        verify(client).fetchPage(2, 1);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_401", "HTTP_403", "HTTP_400", "API_ERROR", "INVALID_RESPONSE"})
    void nonRetryableFailureOnMiddlePageStopsCollection(String errorCode) {
        when(client.fetchPage(1, 1)).thenReturn(page(1000));
        when(client.fetchPage(2, 1)).thenThrow(new YouthPolicyClientException(errorCode, false));
        var result = service.collect(1, 1000);
        assertFalse(result.completed());
        assertEquals(1, result.records().size());
        assertEquals(2, result.pageFailures().getFirst().pageNumber());
        verify(client).fetchPage(1, 1);
        verify(client).fetchPage(2, 1);
        verifyNoMoreInteractions(client);
    }

    @Test
    void unknownTotalStopsAtEmptyPage() {
        when(client.fetchPage(1, 1)).thenReturn(page(null));
        when(client.fetchPage(2, 1)).thenReturn(new YouthPolicyPage(null, List.of()));
        var result = service.collect(1, 1000);
        assertTrue(result.completed());
        verify(client, never()).fetchPage(3, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_503", "CONNECTION_ERROR", "API_ERROR"})
    void repeatedMiddlePageFailuresStopWithoutReachingPageLimit(String errorCode) {
        when(client.fetchPage(1, 1)).thenReturn(page(null));
        when(client.fetchPage(2, 1)).thenThrow(new YouthPolicyClientException(errorCode, true));
        when(client.fetchPage(3, 1)).thenThrow(new YouthPolicyClientException(errorCode, true));
        when(client.fetchPage(4, 1)).thenThrow(new YouthPolicyClientException(errorCode, true));

        var result = service.collect(1, 1000);

        assertFalse(result.completed());
        assertEquals(1, result.records().size());
        assertEquals(List.of(2, 3, 4), result.pageFailures().stream()
                .map(failure -> failure.pageNumber()).toList());
        verify(client, times(4)).fetchPage(anyInt(), eq(1));
        verify(client, never()).fetchPage(5, 1);
    }

    @Test
    void successfulPageResetsConsecutiveFailureLimit() {
        when(client.fetchPage(1, 1)).thenReturn(page(7));
        for (int number : new int[]{2, 3, 5, 6}) {
            when(client.fetchPage(number, 1)).thenThrow(new YouthPolicyClientException("HTTP_503", true));
        }
        when(client.fetchPage(4, 1)).thenReturn(page(7));
        when(client.fetchPage(7, 1)).thenReturn(page(7));

        var result = service.collect(1, 1000);

        assertTrue(result.completed());
        assertEquals(3, result.records().size());
        assertEquals(4, result.pageFailures().size());
        verify(client).fetchPage(7, 1);
        verify(client, never()).fetchPage(8, 1);
    }

    @Test
    void pageLimitIsAnIncompleteSafetyStop() {
        when(client.fetchPage(anyInt(), eq(1))).thenReturn(page(null));
        var result = service.collect(1, 2);
        assertFalse(result.completed());
        assertEquals(2, result.records().size());
        verify(client, never()).fetchPage(3, 1);
    }

    @Test
    void doesNotTreatConfigurationErrorsAsPageFailures() {
        when(client.fetchPage(1, 1)).thenThrow(new IllegalStateException("Missing key"));
        assertThrows(IllegalStateException.class, () -> service.collect(1, 3));
        verify(client, never()).fetchPage(2, 1);
        assertThrows(IllegalArgumentException.class, () -> service.collect(0, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_401", "HTTP_403", "HTTP_400", "API_ERROR", "INVALID_RESPONSE"})
    void stopsOnNonRetryableFirstPageFailure(String errorCode) {
        when(client.fetchPage(1, 1)).thenThrow(new YouthPolicyClientException(errorCode, false));
        var result = service.collect(1, 1000);
        assertFalse(result.completed());
        assertEquals(1, result.pageFailures().size());
        verify(client).fetchPage(1, 1);
        verifyNoMoreInteractions(client);
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

    @Test
    void interruptionStopsBeforeRetry() {
        when(client.fetchPage(1, 1)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new YouthPolicyClientException("CONNECTION_ERROR", true);
        });
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> service.collect(1, 1000));
            verify(client).fetchPage(1, 1);
            verifyNoMoreInteractions(client);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void processesEachPageBeforeFetchingTheNextOne() {
        var processedOffsets = new java.util.ArrayList<Integer>();
        when(client.fetchPage(1, 1)).thenReturn(page(2));
        when(client.fetchPage(2, 1)).thenAnswer(invocation -> {
            assertEquals(List.of(0), processedOffsets);
            return page(2);
        });
        var summary = service.collectPages(1, 2, (offset, rows) -> processedOffsets.add(offset));
        assertEquals(List.of(0, 1), processedOffsets);
        assertEquals(2, summary.fetchedCount());
        assertTrue(summary.completed());
    }

    @Test
    void pageProcessingFailureStopsBeforeFetchingAnotherPage() {
        when(client.fetchPage(1, 1)).thenReturn(page(2));
        assertThrows(IllegalStateException.class, () -> service.collectPages(1, 2,
                (offset, rows) -> { throw new IllegalStateException("processing failed"); }));
        verify(client, never()).fetchPage(2, 1);
    }
}
