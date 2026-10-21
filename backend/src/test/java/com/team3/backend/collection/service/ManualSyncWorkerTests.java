package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualSyncWorkerTests {
    private final SyncJobService jobs = mock(SyncJobService.class);
    private final RawRecordService records = mock(RawRecordService.class);
    private final ManualSyncWorker worker = new ManualSyncWorker(jobs,
            mock(YouthPolicyCollectionService.class), mock(Employment24CollectionService.class),
            records, new RawRecordFingerprint());

    private List<JsonNode> page(int size) {
        return IntStream.range(0, size).mapToObj(i -> (JsonNode) JsonNodeFactory.instance.objectNode()
                .put("plcyNo", "p" + i)).toList();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void splitsPagesIntoOneHundredAndRemainder() {
        ReflectionTestUtils.invokeMethod(worker, "processPage", 10L, CollectionSource.YOUTH_POLICY, 0, page(205));
        ArgumentCaptor<List<RawRecordSaveRequest>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(records, times(3)).saveBatchForSync(batches.capture());
        assertEquals(List.of(100, 100, 5), batches.getAllValues().stream().map(List::size).toList());
        verify(records, never()).saveForSync(any());
        verify(jobs).recordFetchedPage(10L, 205);
    }

    @Test
    void failedBatchRetriesIndividuallyAndKeepsFailureOffset() {
        doThrow(new DataIntegrityViolationException("batch failed")).when(records).saveBatchForSync(anyList());
        doThrow(new DataIntegrityViolationException("record failed")).when(records)
                .saveForSync(argThat(request -> request.externalId().equals("p1")));
        ReflectionTestUtils.invokeMethod(worker, "processPage", 10L, CollectionSource.YOUTH_POLICY, 200, page(3));
        verify(records, times(3)).saveForSync(any());
        verify(jobs).recordFailure(10L, 201, "p1", "STORAGE_FAILED");
    }

    @Test
    void invalidRecordKeepsOriginalIndexAndValidRecords() {
        var page = new java.util.ArrayList<>(page(3));
        page.set(1, JsonNodeFactory.instance.objectNode());
        ReflectionTestUtils.invokeMethod(worker, "processPage", 10L, CollectionSource.YOUTH_POLICY, 100, page);
        verify(records, times(2)).saveForSync(any());
        verify(jobs).recordFailure(10L, 101, null, "INVALID_RECORD");
    }
}
