package com.team3.backend.collection.service;

import com.team3.backend.collection.client.Employment24Client;
import com.team3.backend.collection.dto.external.Employment24Page;
import com.team3.backend.collection.dto.external.Employment24SearchRequest;
import com.team3.backend.collection.exception.Employment24ClientException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Employment24CollectionServiceTests {
    private final Employment24Client client = mock(Employment24Client.class);
    private final Employment24CollectionService service = new Employment24CollectionService(client);
    private final LocalDate today = LocalDate.of(2026, 10, 15);

    @BeforeEach
    void setUp() {
        when(client.fetchPage(any())).thenReturn(new Employment24Page(0, "1", "1", List.of()));
    }

    private Employment24SearchRequest request(String code, int page) {
        return Employment24SearchRequest.forNextSixMonths(code, today, page, 1);
    }

    private Employment24Page page(Integer count, int number) {
        return new Employment24Page(count, String.valueOf(number), "1",
                List.of(JsonNodeFactory.instance.objectNode().put("trprId", "course-" + number)));
    }

    @Test
    void queriesAll24CodesWithOneFixedSixMonthWindow() {
        var result = service.collect(today, 1, 3);
        var captor = ArgumentCaptor.forClass(Employment24SearchRequest.class);
        verify(client, times(24)).fetchPage(captor.capture());
        assertEquals("01", captor.getAllValues().getFirst().srchNcs1());
        assertEquals("24", captor.getAllValues().getLast().srchNcs1());
        for (var request : captor.getAllValues()) {
            assertEquals(today, request.srchTraStDt());
            assertEquals(LocalDate.of(2027, 4, 15), request.srchTraEndDt());
            assertEquals(1, request.pageNum());
        }
        assertTrue(result.completed());
        assertTrue(result.pageFailures().isEmpty());
    }

    @Test
    void continuesAfterExhaustingRetriesOnLaterPage() {
        when(client.fetchPage(request("01", 1))).thenReturn(page(3, 1));
        when(client.fetchPage(request("01", 2))).thenThrow(new Employment24ClientException("HTTP_503", true));
        when(client.fetchPage(request("01", 3))).thenReturn(page(3, 3));

        var result = service.collect(today, 1, 5);

        assertEquals(2, result.records().size());
        assertEquals(1, result.pageFailures().size());
        var failure = result.pageFailures().getFirst();
        assertEquals(request("01", 2), failure.request());
        assertEquals("HTTP_503", failure.errorCode());
        assertTrue(failure.retryable());
        assertTrue(result.completed());
        assertTrue(result.incompleteNcsCodes().isEmpty());
        verify(client, times(3)).fetchPage(request("01", 2));
        verify(client).fetchPage(request("01", 3));
        verify(client).fetchPage(request("02", 1));
        verify(client, never()).fetchPage(request("01", 4));
    }

    @Test
    void nonRetryableMiddlePageFailureStillContinues() {
        when(client.fetchPage(request("01", 1))).thenReturn(page(3, 1));
        when(client.fetchPage(request("01", 2))).thenThrow(new Employment24ClientException("INVALID_RESPONSE", false));
        when(client.fetchPage(request("01", 3))).thenReturn(page(3, 3));
        var result = service.collect(today, 1, 5);
        assertEquals(2, result.records().size());
        assertTrue(result.completed());
        assertEquals(1, result.pageFailures().size());
        verify(client, times(1)).fetchPage(request("01", 2));
        verify(client).fetchPage(request("01", 3));
    }

    @Test
    void failedLastPageCompletesTraversalButPreservesFailure() {
        when(client.fetchPage(request("01", 1))).thenReturn(page(2, 1));
        when(client.fetchPage(request("01", 2))).thenThrow(new Employment24ClientException("HTTP_503", true));
        var result = service.collect(today, 1, 5);
        assertTrue(result.completed());
        assertEquals(1, result.pageFailures().size());
        verify(client, times(3)).fetchPage(request("01", 2));
        verify(client, never()).fetchPage(request("01", 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationFailureOnMiddlePageStillStopsEverything(int status) {
        when(client.fetchPage(request("01", 1))).thenReturn(page(3, 1));
        when(client.fetchPage(request("01", 2))).thenThrow(new Employment24ClientException("HTTP_" + status, false));
        var result = service.collect(today, 1, 5);
        assertFalse(result.completed());
        assertEquals(24, result.incompleteNcsCodes().size());
        assertEquals(1, result.records().size());
        assertEquals(1, result.pageFailures().size());
        verify(client, times(1)).fetchPage(request("01", 2));
        verify(client, never()).fetchPage(request("01", 3));
        verify(client, never()).fetchPage(request("02", 1));
    }

    @Test
    void listGrowthUsesLatestCountAndPreservesRepeatedRawRecords() {
        when(client.fetchPage(request("01", 1))).thenReturn(page(2, 1));
        when(client.fetchPage(request("01", 2))).thenReturn(new Employment24Page(3, "2", "1", page(3, 1).srchList()));
        when(client.fetchPage(request("01", 3))).thenReturn(page(3, 3));
        var result = service.collect(today, 1, 5);
        assertEquals(3, result.records().size());
        assertEquals(result.records().get(0), result.records().get(1));
        assertTrue(result.completed());
        verify(client).fetchPage(request("01", 3));
        verify(client, never()).fetchPage(request("01", 4));
    }

    @Test
    void laterPageFailureAtLimitDoesNotHideIncompleteTraversal() {
        when(client.fetchPage(request("01", 1))).thenReturn(page(3, 1));
        when(client.fetchPage(request("01", 2))).thenThrow(new Employment24ClientException("HTTP_503", true));
        var result = service.collect(today, 1, 2);
        assertFalse(result.completed());
        assertEquals(List.of("01"), result.incompleteNcsCodes());
        assertEquals(1, result.pageFailures().size());
        verify(client, never()).fetchPage(request("01", 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationFailureStopsAllRemainingCategories(int status) {
        when(client.fetchPage(request("01", 1))).thenReturn(page(1, 1));
        when(client.fetchPage(request("02", 1))).thenThrow(new Employment24ClientException("HTTP_" + status, false));
        var result = service.collect(today, 1, 1000);
        assertEquals(1, result.records().size());
        assertFalse(result.completed());
        assertEquals(23, result.incompleteNcsCodes().size());
        assertEquals("02", result.incompleteNcsCodes().getFirst());
        assertEquals("24", result.incompleteNcsCodes().getLast());
        verify(client, times(1)).fetchPage(request("02", 1));
        verify(client, never()).fetchPage(request("02", 2));
        verify(client, never()).fetchPage(request("03", 1));
    }

    @Test
    void nonRetryableFirstPageFailureStopsCategoryImmediately() {
        when(client.fetchPage(request("01", 1))).thenThrow(new Employment24ClientException("INVALID_RESPONSE", false));
        var result = service.collect(today, 1, 1000);
        assertEquals(List.of("01"), result.incompleteNcsCodes());
        assertEquals(1, result.pageFailures().size());
        verify(client, times(1)).fetchPage(request("01", 1));
        verify(client, never()).fetchPage(request("01", 2));
        verify(client).fetchPage(request("24", 1));
    }

    @Test
    void successfulRetryResumesWithoutRecordingPermanentFailure() {
        when(client.fetchPage(request("01", 1)))
                .thenThrow(new Employment24ClientException("HTTP_503", true)).thenReturn(page(1, 1));
        var result = service.collect(today, 1, 1000);
        assertTrue(result.completed());
        assertTrue(result.pageFailures().isEmpty());
        assertEquals(1, result.records().size());
        verify(client, times(2)).fetchPage(request("01", 1));
        verify(client, never()).fetchPage(request("01", 2));
    }
    @Test
    void marksLimitedCategoryIncompleteAndContinuesOtherCategories() {
        when(client.fetchPage(argThat(r -> r != null && r.srchNcs1().equals("01"))))
                .thenThrow(new Employment24ClientException("CONNECTION_ERROR", true));

        var result = service.collect(today, 1, 3);

        assertFalse(result.completed());
        assertEquals(List.of("01"), result.incompleteNcsCodes());
        assertEquals(1, result.pageFailures().size());
        verify(client, times(3)).fetchPage(request("01", 1));
        verify(client, never()).fetchPage(request("01", 2));
        verify(client, never()).fetchPage(request("01", 4));
        verify(client).fetchPage(request("24", 1));
    }

    @Test
    void allCategoriesFailingFirstPageMakeAtMost72Calls() {
        when(client.fetchPage(any())).thenThrow(new Employment24ClientException("HTTP_503", true));
        var result = service.collect(today, 1, 1000);
        assertEquals(24, result.incompleteNcsCodes().size());
        assertEquals(24, result.pageFailures().size());
        var captor = ArgumentCaptor.forClass(Employment24SearchRequest.class);
        verify(client, times(72)).fetchPage(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(r -> r.pageNum() == 1));
    }
    @Test
    void marksLimitIncompleteEvenWithoutPageErrors() {
        when(client.fetchPage(request("01", 1))).thenReturn(page(4, 1));
        when(client.fetchPage(request("01", 2))).thenReturn(page(4, 2));
        var result = service.collect(today, 1, 2);
        assertFalse(result.completed());
        assertEquals(List.of("01"), result.incompleteNcsCodes());
        assertTrue(result.pageFailures().isEmpty());
    }

    @Test
    void doesNotHideConfigurationErrors() {
        when(client.fetchPage(request("01", 1))).thenThrow(new IllegalStateException("Missing key"));
        assertThrows(IllegalStateException.class, () -> service.collect(today, 1, 3));
        verify(client, never()).fetchPage(request("02", 1));
    }

    @Test
    void rejectsInvalidLimitsBeforeCallingClient() {
        clearInvocations(client);
        assertThrows(IllegalArgumentException.class, () -> service.collect(today, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> service.collect(today, 101, 1000));
        assertThrows(IllegalArgumentException.class, () -> service.collect(today, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> service.collect(today, 100, 1001));
        verifyNoInteractions(client);
    }
}
