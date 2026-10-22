package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualSyncWorkerTests {
    private final SyncJobService jobs = mock(SyncJobService.class);
    private final RawRecordService records = mock(RawRecordService.class);
    private final YouthPolicyCollectionService youthPolicy = mock(YouthPolicyCollectionService.class);
    private final ManualSyncWorker worker = new ManualSyncWorker(jobs,
            youthPolicy, mock(Employment24CollectionService.class),
            records, new RawRecordFingerprint());

    private List<JsonNode> page(int size) {
        return IntStream.range(0, size).mapToObj(i -> (JsonNode) JsonNodeFactory.instance.objectNode()
                .put("plcyNo", "p" + i)).toList();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void splitsPagesIntoOneHundredAndRemainder() {
        worker.processPage(10L, CollectionSource.YOUTH_POLICY, 0, page(205));
        ArgumentCaptor<List<RawRecordSaveRequest>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(records, times(3)).saveBatchForSync(batches.capture());
        assertEquals(List.of(100, 100, 5), batches.getAllValues().stream().map(List::size).toList());
        verify(records, never()).saveForSync(any());
        verify(jobs).recordFetchedPage(10L, 205);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedBatchRetriesIndividuallyAndKeepsFailureOffset(CapturedOutput output) {
        doThrow(new DataIntegrityViolationException("batch failed")).when(records).saveBatchForSync(anyList());
        doThrow(new DataIntegrityViolationException("record failed")).when(records)
                .saveForSync(argThat(request -> request.externalId().equals("p1")));
        worker.processPage(10L, CollectionSource.YOUTH_POLICY, 200, page(3));
        verify(records, times(3)).saveForSync(any());
        verify(jobs).recordFailure(10L, 201, "p1", "STORAGE_FAILED");
        assertTrue(output.getOut().contains("jobId=10 source=YOUTH_POLICY offset=200 exceptionType=DataIntegrityViolationException retry=individual"));
    }

    @Test
    void invalidRecordKeepsOriginalIndexAndValidRecords() {
        var page = new ArrayList<>(page(3));
        page.set(1, JsonNodeFactory.instance.objectNode());
        worker.processPage(10L, CollectionSource.YOUTH_POLICY, 100, page);
        verify(records).saveBatchForSync(argThat(batch -> batch.stream()
                .map(RawRecordSaveRequest::externalId).toList().equals(List.of("p0", "p2"))));
        verify(records, never()).saveForSync(any());
        verify(jobs).recordFailure(10L, 101, null, "INVALID_RECORD");
        verify(jobs, times(1)).recordFailure(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    void filteredBatchFailureRetriesOnlyValidRecordsWithOriginalIndexes() {
        var page = new ArrayList<>(page(3));
        page.set(1, JsonNodeFactory.instance.objectNode());
        doThrow(new DataIntegrityViolationException("batch failed")).when(records).saveBatchForSync(anyList());
        doThrow(new DataIntegrityViolationException("record failed")).when(records)
                .saveForSync(argThat(request -> request.externalId().equals("p2")));
        worker.processPage(10L, CollectionSource.YOUTH_POLICY, 100, page);
        verify(records, times(2)).saveForSync(any());
        verify(jobs).recordFailure(10L, 101, null, "INVALID_RECORD");
        verify(jobs).recordFailure(10L, 102, "p2", "STORAGE_FAILED");
        verify(jobs, times(2)).recordFailure(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    void skipsStorageWhenEveryRecordIsInvalid() {
        worker.processPage(10L, CollectionSource.YOUTH_POLICY, 200,
                List.of(JsonNodeFactory.instance.objectNode()));
        verifyNoInteractions(records);
        verify(jobs).recordFetchedPage(10L, 1);
        verify(jobs).recordFailure(10L, 200, null, "INVALID_RECORD");
    }

    @Test
    void interruptedBatchMarksJobInterruptedWithoutIndividualRetry() {
        doAnswer(invocation -> {
            BiConsumer<Integer, List<JsonNode>> handler = invocation.getArgument(2);
            handler.accept(0, page(3));
            return null;
        }).when(youthPolicy).collectPages(eq(100), eq(1), any());
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted");
        }).when(records).saveBatchForSync(anyList());
        try {
            worker.run(10L, CollectionSource.YOUTH_POLICY, 100, 1);
            verify(jobs).fail(10L, "수집 실행이 중단되었습니다.", true);
            verify(jobs, never()).complete(anyLong(), anyBoolean());
            verify(records, never()).saveForSync(any());
        } finally {
            Thread.interrupted();
        }
    }
}
