package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.entity.ExternalSource;
import com.team3.backend.collection.domain.entity.RawExternalRecord;
import com.team3.backend.collection.domain.entity.SyncHistory;
import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.domain.repository.ExternalSourceRepository;
import com.team3.backend.collection.domain.repository.RawExternalRecordRepository;
import com.team3.backend.collection.domain.repository.SyncHistoryRepository;
import com.team3.backend.collection.domain.repository.RawNormalizationRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RawRecordServiceTests {
    private final SyncHistoryRepository syncHistories = mock(SyncHistoryRepository.class);
    private final ExternalSourceRepository sources = mock(ExternalSourceRepository.class);
    private final RawExternalRecordRepository records = mock(RawExternalRecordRepository.class);
    private final RawRecordService service = new RawRecordService(syncHistories, sources, records, new RawRecordFingerprint(),
            mock(RawNormalizationRequestRepository.class));
    private final OffsetDateTime startedAt = OffsetDateTime.parse("2026-10-16T10:00:00+09:00");
    private final ExternalSource source = new ExternalSource("YOUTH_POLICY", "온통청년", "https://example.com", true, startedAt);
    private final SyncHistory sync = new SyncHistory(source, "FULL", startedAt);
    private final List<RawExternalRecord> snapshots = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(source, "id", 1L);
        when(syncHistories.findByIdForUpdate(10L)).thenReturn(Optional.of(sync));
        when(sources.findByIdForUpdate(1L)).thenReturn(Optional.of(source));
        when(records.findFirstBySourceIdAndExternalIdOrderByIdDesc(1L, "policy-1"))
                .thenAnswer(invocation -> snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.getLast()));
        when(records.save(any(RawExternalRecord.class))).thenAnswer(invocation -> {
            RawExternalRecord snapshot = invocation.getArgument(0);
            ReflectionTestUtils.setField(snapshot, "id", (long) snapshots.size() + 1);
            snapshots.add(snapshot);
            return snapshot;
        });
    }

    private RawRecordSaveRequest request(String content, int minutes) {
        return new RawRecordSaveRequest(10L, "policy-1", "hash-" + content,
                JsonNodeFactory.instance.objectNode().put("title", content), startedAt.plusMinutes(minutes));
    }

    @Test
    void batchLocksOnceAndPreservesRepeatedIdsAndProgress() {
        var batch = List.of(request("A", 1), request("A", 2), request("B", 3)).stream()
                .map(request -> new RawRecordSaveRequest(request.syncId(), request.externalId(),
                        new RawRecordFingerprint().hash(request.payload()), request.payload(), request.collectedAt()))
                .toList();
        service.saveBatchForSync(batch);
        verify(syncHistories).findByIdForUpdate(10L);
        verify(sources).findByIdForUpdate(1L);
        assertEquals(2, snapshots.size());
        assertEquals(1, sync.getNewCount());
        assertEquals(1, sync.getUpdatedCount());
        assertEquals(1, sync.getUnchangedCount());
    }

    @Test
    void batchRejectsMixedExecutions() {
        var other = new RawRecordSaveRequest(11L, "policy-2", "hash",
                request("B", 2).payload(), startedAt.plusMinutes(2));
        assertThrows(IllegalArgumentException.class,
                () -> service.saveBatchForSync(List.of(request("A", 1), other)));
        verifyNoInteractions(syncHistories, sources, records);
    }

    @Test
    void interruptedBatchStopsBeforeSaving() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,
                    () -> service.saveBatchForSync(List.of(request("A", 1))));
            verifyNoInteractions(records);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void savesFirstSnapshotAfterLockingSource() {
        var response = service.save(request("A", 1));
        assertEquals(1L, response.rawRecordId());
        assertEquals("YOUTH_POLICY", response.source());
        assertEquals("A", response.payload().path("title").asString());
        assertSame(sync, snapshots.getFirst().getSyncHistory());
        var order = inOrder(syncHistories, sources, records);
        order.verify(syncHistories).findByIdForUpdate(10L);
        order.verify(sources).findByIdForUpdate(1L);
        order.verify(records).findFirstBySourceIdAndExternalIdOrderByIdDesc(1L, "policy-1");
        order.verify(records).save(any(RawExternalRecord.class));
    }

    @Test
    void repeatedContentUpdatesObservationWithoutCreatingSnapshot() {
        service.save(request("A", 1));
        var response = service.save(request("A", 2));
        assertEquals(1, snapshots.size());
        assertEquals(startedAt.plusMinutes(1), response.collectedAt());
        assertEquals(startedAt.plusMinutes(2), response.lastSeenAt());
        verify(records, times(1)).save(any(RawExternalRecord.class));
    }

    @Test
    void preservesBothChangesWhenContentReturnsToOriginalValue() {
        service.save(request("A", 1));
        service.save(request("B", 2));
        service.save(request("A", 3));
        assertEquals(List.of(hash("A"), hash("B"), hash("A")),
                snapshots.stream().map(RawExternalRecord::getPayloadHash).toList());
        assertEquals("A", snapshots.getFirst().getPayload().path("title").asString());
    }

    private String hash(String content) {
        return new RawRecordFingerprint().hash(request(content, 1).payload());
    }

    @Test
    void ignoresClientHashesAndDetectsChangesFromPayload() {
        var first = service.save(request("A", 1));
        var samePayload = request("A", 2);
        var repeated = service.save(new RawRecordSaveRequest(10L, "policy-1", "different-client-hash",
                samePayload.payload(), samePayload.collectedAt()));
        assertEquals(first.rawRecordId(), repeated.rawRecordId());
        var changedPayload = request("B", 3);
        service.save(new RawRecordSaveRequest(10L, "policy-1", "hash-A",
                changedPayload.payload(), changedPayload.collectedAt()));
        assertEquals(List.of(hash("A"), hash("B")),
                snapshots.stream().map(RawExternalRecord::getPayloadHash).toList());
    }

    @Test
    void comparesLegacyHashesUsingOriginalJson() {
        service.save(request("A", 1));
        ReflectionTestUtils.setField(snapshots.getFirst(), "payloadHash", "legacy-hash");
        service.save(request("A", 2));
        assertEquals(1, snapshots.size());
        assertEquals(startedAt.plusMinutes(2), snapshots.getFirst().getLastSeenAt());
    }

    @Test
    void rejectsOlderObservationForBothSameAndDifferentContent() {
        service.save(request("A", 2));
        assertThrows(IllegalArgumentException.class, () -> service.save(request("A", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.save(request("B", 1)));
        assertEquals(1, snapshots.size());
        assertEquals(startedAt.plusMinutes(2), snapshots.getFirst().getLastSeenAt());
    }

    @Test
    void rejectsMissingOrFinishedExecution() {
        when(syncHistories.findByIdForUpdate(10L)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> service.save(request("A", 1)));
        when(syncHistories.findByIdForUpdate(10L)).thenReturn(Optional.of(sync));
        ReflectionTestUtils.setField(sync, "status", SyncJobStatus.SUCCESS);
        assertThrows(IllegalStateException.class, () -> service.save(request("A", 1)));
        verifyNoInteractions(sources, records);
    }

    @Test
    void rejectsObservationBeforeExecutionAndMissingSource() {
        assertThrows(IllegalArgumentException.class, () -> service.save(request("A", -1)));
        when(sources.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> service.save(request("A", 1)));
        verifyNoInteractions(records);
    }

    @Test
    void propagatesStorageFailure() {
        when(records.save(any(RawExternalRecord.class))).thenThrow(new IllegalStateException("DB failure"));
        assertThrows(IllegalStateException.class, () -> service.save(request("A", 1)));
    }
}
