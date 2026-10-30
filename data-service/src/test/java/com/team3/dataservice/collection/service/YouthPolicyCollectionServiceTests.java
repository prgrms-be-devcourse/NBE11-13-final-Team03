package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.client.YouthPolicyClient;
import com.team3.dataservice.collection.dto.external.YouthPolicyPage;
import com.team3.dataservice.collection.exception.YouthPolicyClientException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** HTTP 오류 분류는 YouthPolicyClientTests, 여기서는 재시도 가능 여부별 수집 흐름을 검증한다. */
class YouthPolicyCollectionServiceTests {
    private final YouthPolicyClient client = mock(YouthPolicyClient.class);
    private final YouthPolicyCollectionService service = new YouthPolicyCollectionService(client);

    private YouthPolicyPage page(Integer totalCount) {
        return new YouthPolicyPage(totalCount, List.of(JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void continuesAfterFailureAndReturnsItsDetails() {
        String errorCode = "HTTP_403";
        when(client.fetchPage(1, 1)).thenReturn(page(3));
        when(client.fetchPage(2, 1)).thenThrow(new YouthPolicyClientException(errorCode, true));
        when(client.fetchPage(3, 1)).thenReturn(page(3));

        var result = service.collect(1, 10);

        assertTrue(result.completed());
        assertEquals(2, result.records().size());
        assertEquals(1, result.pageFailures().size());
        var failure = result.pageFailures().getFirst();
        assertEquals(2, failure.pageNumber());
        assertEquals(errorCode, failure.errorCode());
        assertTrue(failure.retryable());
        verify(client, times(3)).fetchPage(2, 1);
        verify(client).fetchPage(3, 1);
        verify(client, never()).fetchPage(4, 1);
    }

    @Test
    void stopsAfterThreeFirstPageAttempts() {
        String errorCode = "HTTP_403";
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

    @Test
    void nonRetryableFailureOnMiddlePageStopsCollection() {
        String errorCode = "HTTP_401";
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

    @Test
    void repeatedMiddlePageFailuresStopWithoutReachingPageLimit() {
        String errorCode = "HTTP_403";
        when(client.fetchPage(1, 1)).thenReturn(page(null));
        when(client.fetchPage(2, 1)).thenThrow(new YouthPolicyClientException(errorCode, true));
        when(client.fetchPage(3, 1)).thenThrow(new YouthPolicyClientException(errorCode, true));
        when(client.fetchPage(4, 1)).thenThrow(new YouthPolicyClientException(errorCode, true));

        var result = service.collect(1, 1000);

        assertFalse(result.completed());
        assertEquals(1, result.records().size());
        assertEquals(List.of(2, 3, 4), result.pageFailures().stream()
                .map(failure -> failure.pageNumber()).toList());
        verify(client, times(10)).fetchPage(anyInt(), eq(1));
        verify(client, never()).fetchPage(5, 1);
    }

    @Test
    void middlePageRecoveryLeavesOnlyTheFinalSuccessfulResult() {
        when(client.fetchPage(1, 1)).thenReturn(page(2));
        when(client.fetchPage(2, 1)).thenThrow(new YouthPolicyClientException("HTTP_503", true))
                .thenThrow(new YouthPolicyClientException("HTTP_429", true)).thenReturn(page(2));
        var result = service.collect(1, 2);
        assertTrue(result.completed());
        assertEquals(2, result.records().size());
        assertTrue(result.pageFailures().isEmpty());
        verify(client, times(3)).fetchPage(2, 1);
    }

    @Test
    void retriesPageThirtyBeforeSavingItOnceAndCollectingTheRemainingPages() {
        String errorCode = "HTTP_403";
        var rows = java.util.Collections.nCopies(100, (tools.jackson.databind.JsonNode) JsonNodeFactory.instance.objectNode());
        when(client.fetchPage(anyInt(), eq(100))).thenReturn(new YouthPolicyPage(3600, rows));
        when(client.fetchPage(30, 100)).thenThrow(new YouthPolicyClientException(errorCode, true))
                .thenThrow(new YouthPolicyClientException(errorCode, true))
                .thenReturn(new YouthPolicyPage(3600, rows));
        var offsets = new java.util.ArrayList<Integer>();

        var result = service.collectPages(100, 1000, (offset, page) -> offsets.add(offset));

        assertTrue(result.completed());
        assertEquals(3600, result.fetchedCount());
        assertTrue(result.pageFailures().isEmpty());
        assertEquals(java.util.stream.IntStream.range(0, 36).map(n -> n * 100).boxed().toList(), offsets);
        verify(client, times(3)).fetchPage(30, 100);
        verify(client).fetchPage(36, 100);
        verify(client, never()).fetchPage(37, 100);
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
    }

    @Test
    void stopsOnNonRetryableFirstPageFailure() {
        String errorCode = "HTTP_401";
        when(client.fetchPage(1, 1)).thenThrow(new YouthPolicyClientException(errorCode, false));
        var result = service.collect(1, 1000);
        assertFalse(result.completed());
        assertEquals(1, result.pageFailures().size());
        verify(client).fetchPage(1, 1);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "-1, 1", "1, 0", "1, -1"})
    void invalidPageLimitsFailBeforeAnyExternalRequest(int pageSize, int maxPages) {
        assertThrows(IllegalArgumentException.class, () -> service.collect(pageSize, maxPages));
        verifyNoInteractions(client);
    }

    @Test
    void missingPageHandlerFailsBeforeAnyExternalRequest() {
        assertThrows(NullPointerException.class, () -> service.collectPages(100, 1000, null));
        verifyNoInteractions(client);
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
