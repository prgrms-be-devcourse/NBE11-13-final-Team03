package com.team3.backend.client.grpc;

import com.google.protobuf.Timestamp;
import com.team3.contracts.collection.v1.*;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CollectionProtoMapperTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final CollectionProtoMapper mapper = new CollectionProtoMapper(json);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-23T09:00:00.123456789+09:00");

    @Test
    void mapsEveryJobAndNestedFailureField() throws Exception {
        var timestamp = mapper.timestamp(now);
        var job = SyncJob.newBuilder().setJobId(42).setSource(CollectionSource.EMPLOYMENT24)
                .setStatus(SyncJobStatus.PARTIAL_SUCCESS).setStartedAt(timestamp).setCompletedAt(timestamp)
                .setFetchedCount(101).setSuccessCount(83).setFailedRecordCount(11).setPendingCount(7)
                .setFailedPageCount(2).setNewCount(29).setUpdatedCount(31).setUnchangedCount(23).setFailureReason("partial")
                .addPageFailures(PageFailure.newBuilder().setPageNumber(3).setErrorCode("PAGE_ERROR").setErrorMessage("page").setRetryable(true))
                .addEmployment24PageFailures(Employment24PageFailure.newBuilder().setRequestJson("{\"srchNcs1\":\"01\",\"pageNum\":7}")
                        .setErrorCode("EMP_ERROR").setErrorMessage("employment"))
                .addIncompleteNcsCodes("01")
                .addRecordFailures(RecordFailure.newBuilder().setRecordResultId(8).setExternalId("p1").setPageNumber(3).setItemIndex(2)
                        .setRawRecordId(9).setStatus("FAILED").setResultType("UPDATED").setErrorCode("RECORD_ERROR")
                        .setErrorMessage("record").setProcessedAt(timestamp)).build();
        var value = mapper.job(SyncJob.parseFrom(job.toByteArray()));
        assertEquals(42L, value.jobId()); assertEquals("EMPLOYMENT24", value.source());
        assertEquals("PARTIAL_SUCCESS", value.status()); assertEquals(now.toInstant(), value.startedAt().toInstant());
        assertEquals(now.toInstant(), value.completedAt().toInstant()); assertNull(value.totalCount());
        assertEquals(101, value.fetchedCount()); assertEquals(83, value.successCount()); assertEquals(11, value.failedRecordCount());
        assertEquals(7, value.pendingCount()); assertEquals(2, value.failedPageCount()); assertEquals(29, value.newCount());
        assertEquals(31, value.updatedCount()); assertEquals(23, value.unchangedCount()); assertEquals("partial", value.failureReason());
        assertEquals(java.util.List.of("01"), value.incompleteNcsCodes());
        assertEquals(new com.team3.backend.collection.dto.response.SyncPageFailureResponse(3, "PAGE_ERROR", "page", true), value.pageFailures().getFirst());
        var employment = value.employment24PageFailures().getFirst();
        assertEquals("EMP_ERROR", employment.errorCode()); assertEquals("employment", employment.errorMessage());
        assertFalse(employment.retryable()); assertEquals("01", employment.request().path("srchNcs1").asString());
        assertEquals(7, employment.request().path("pageNum").asInt());
        var record = value.recordFailures().getFirst();
        assertEquals(8L, record.recordResultId()); assertEquals("p1", record.externalId()); assertEquals(3, record.pageNumber());
        assertEquals(2, record.itemIndex()); assertEquals(9L, record.rawRecordId()); assertEquals("FAILED", record.status());
        assertEquals("UPDATED", record.resultType()); assertEquals("RECORD_ERROR", record.errorCode());
        assertEquals("record", record.errorMessage()); assertEquals(now.toInstant(), record.processedAt().toInstant());
    }

    @Test
    void missingOptionalFieldsRemainNullAndAllStatusesArePreserved() {
        for (var status : SyncJobStatus.values()) {
            if (status == SyncJobStatus.UNRECOGNIZED) continue;
            var value = mapper.job(SyncJob.newBuilder().setStatus(status).build());
            assertEquals(status.name(), value.status()); assertNull(value.totalCount());
            assertNull(value.completedAt()); assertNull(value.failureReason());
        }
        var failure = mapper.job(SyncJob.newBuilder().addRecordFailures(RecordFailure.newBuilder().setStatus("FAILED")).build())
                .recordFailures().getFirst();
        assertNull(failure.recordResultId()); assertNull(failure.pageNumber()); assertNull(failure.itemIndex());
        assertNull(failure.rawRecordId()); assertNull(failure.resultType()); assertNull(failure.processedAt());
    }

    @Test
    void rawJsonAndTimestampRoundTripPreserveLargeIntegersAndNanoseconds() throws Exception {
        var payload = json.readTree("{\"z\":900719925474099312345,\"a\":{\"y\":2,\"x\":1}}");
        var request = mapper.save(new RawRecordSaveRequest(7L, "p1", "ignored", payload, now));
        assertEquals(payload, json.readTree(request.getPayloadJson()));
        assertEquals(now.toInstant(), mapper.dateTime(request.getCollectedAt()).toInstant());
        var response = mapper.raw(RawRecord.newBuilder().setRawRecordId(9).setSource(CollectionSource.YOUTH_POLICY)
                .setExternalId("p1").setPayloadHash("server-hash").setPayloadJson(request.getPayloadJson())
                .setCollectedAt(request.getCollectedAt()).setLastSeenAt(request.getCollectedAt()).build());
        assertEquals(9L, response.rawRecordId()); assertEquals("YOUTH_POLICY", response.source());
        assertEquals("p1", response.externalId()); assertEquals("server-hash", response.payloadHash());
        assertEquals(payload, response.payload()); assertEquals(now.toInstant(), response.collectedAt().toInstant());
        assertEquals(now.toInstant(), response.lastSeenAt().toInstant());
    }

    @Test
    void invalidRawInputAndTimestampsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> mapper.save(new RawRecordSaveRequest(null, "p1", null, json.readTree("{}"), now)));
        assertThrows(IllegalArgumentException.class, () -> mapper.save(new RawRecordSaveRequest(1L, " ", null, json.readTree("{}"), now)));
        assertThrows(IllegalArgumentException.class, () -> mapper.save(new RawRecordSaveRequest(1L, "p1", null, null, now)));
        assertThrows(IllegalArgumentException.class, () -> mapper.save(new RawRecordSaveRequest(1L, "p1", null, json.readTree("{}"), null)));
        assertThrows(IllegalArgumentException.class, () -> mapper.dateTime(Timestamp.newBuilder().setNanos(-1).build()));
        assertThrows(IllegalArgumentException.class, () -> mapper.dateTime(Timestamp.newBuilder().setSeconds(253402300800L).build()));
    }
}
