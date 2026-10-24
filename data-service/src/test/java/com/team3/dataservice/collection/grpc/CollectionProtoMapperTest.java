package com.team3.dataservice.collection.grpc;

import com.team3.contracts.collection.v1.*;
import com.team3.dataservice.collection.domain.RecordProcessStatus;
import com.team3.dataservice.collection.domain.RecordResultType;
import com.team3.dataservice.collection.dto.external.Employment24SearchRequest;
import com.team3.dataservice.collection.dto.response.*;
import com.team3.dataservice.collection.service.RawRecordFingerprint;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CollectionProtoMapperTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final CollectionProtoMapper mapper = new CollectionProtoMapper(json);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-23T23:59:59.123456789+09:00");

    @Test
    void mapsEveryJobFieldAndNestedFailureThroughProtobufWire() throws Exception {
        var request = new Employment24SearchRequest("01", LocalDate.of(2026, 10, 23), LocalDate.of(2027, 4, 23), 7, 50);
        var record = new SyncRecordResultResponse(8L, "external", 7, 2, 9L, RecordProcessStatus.FAILED,
                RecordResultType.UPDATED, "RECORD_ERROR", "record failed", now);
        var value = new SyncJobResponse(42L, "EMPLOYMENT24", com.team3.dataservice.collection.domain.SyncJobStatus.PARTIAL_SUCCESS,
                now, now.plusSeconds(2), null, 101, 83, 11, 7, 2, 29, 31, 23,
                List.of(new SyncPageFailureResponse(3, "PAGE_ERROR", "page failed", true)),
                List.of(new Employment24PageFailureResponse(request, "EMPLOYMENT_ERROR", "request failed", false)),
                List.of("01", "02"), List.of(record), "partial failure");
        var result = SyncJob.parseFrom(mapper.job(value, true).toByteArray());
        assertEquals(value.jobId().longValue(), result.getJobId());
        assertEquals(value.source(), result.getSource().name());
        assertEquals(value.status().name(), result.getStatus().name());
        assertEquals(value.startedAt().toInstant(), mapper.dateTime(result.getStartedAt()).toInstant());
        assertEquals(value.completedAt().toInstant(), mapper.dateTime(result.getCompletedAt()).toInstant());
        assertEquals(value.fetchedCount(), result.getFetchedCount());
        assertEquals(value.successCount(), result.getSuccessCount());
        assertEquals(value.failedRecordCount(), result.getFailedRecordCount());
        assertEquals(value.pendingCount(), result.getPendingCount());
        assertEquals(value.failedPageCount(), result.getFailedPageCount());
        assertEquals(value.newCount(), result.getNewCount());
        assertEquals(value.updatedCount(), result.getUpdatedCount());
        assertEquals(value.unchangedCount(), result.getUnchangedCount());
        assertEquals(value.incompleteNcsCodes(), result.getIncompleteNcsCodesList());
        assertEquals(value.failureReason(), result.getFailureReason());
        assertEquals(PageFailure.newBuilder().setPageNumber(3).setErrorCode("PAGE_ERROR")
                .setErrorMessage("page failed").setRetryable(true).build(), result.getPageFailures(0));
        var page = result.getEmployment24PageFailures(0);
        assertEquals(request, json.readValue(page.getRequestJson(), Employment24SearchRequest.class));
        assertEquals("EMPLOYMENT_ERROR", page.getErrorCode());
        assertEquals("request failed", page.getErrorMessage());
        assertFalse(page.getRetryable());
        assertEquals(RecordFailure.newBuilder().setRecordResultId(8).setExternalId("external").setPageNumber(7)
                .setItemIndex(2).setRawRecordId(9).setStatus("FAILED").setResultType("UPDATED")
                .setErrorCode("RECORD_ERROR").setErrorMessage("record failed").setProcessedAt(mapper.timestamp(now)).build(),
                result.getRecordFailures(0));
        var list = mapper.job(value, false);
        assertEquals(result.toBuilder().clearRecordFailures().build(), list);
    }

    @Test
    void preservesAbsentFieldsAndAllStatuses() {
        for (var status : com.team3.dataservice.collection.domain.SyncJobStatus.values()) {
            var value = new SyncJobResponse(1L, "YOUTH_POLICY", status, now, null, null,
                    0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), null);
            var result = mapper.job(value, true);
            assertEquals(status.name(), result.getStatus().name());
            assertFalse(result.hasCompletedAt());
            assertFalse(result.hasFailureReason());
        }
    }

    @Test
    void retainsPayloadHashAcrossJsonAndProtobufRoundTrip() throws Exception {
        var payload = json.readTree("{\"z\":900719925474099312345,\"a\":{\"y\":2,\"x\":1},\"fraction\":1.25}");
        var hash = new RawRecordFingerprint().hash(payload);
        var raw = RawRecord.parseFrom(mapper.raw(new RawRecordResponse(3L, "YOUTH_POLICY", "p1", hash, now,
                now.plusSeconds(10), payload)).toByteArray());
        var request = mapper.save(SaveRawRecordRequest.newBuilder().setSyncId(1).setExternalId(raw.getExternalId())
                .setPayloadJson(raw.getPayloadJson()).setCollectedAt(raw.getCollectedAt()).build());
        assertEquals(hash, new RawRecordFingerprint().hash(request.payload()));
        assertEquals(hash, new RawRecordFingerprint().hash(json.readTree(
                "{\"fraction\":1.25,\"a\":{\"x\":1,\"y\":2},\"z\":900719925474099312345}")));
        assertEquals(now.toInstant(), request.collectedAt().toInstant());
        assertEquals(now.plusSeconds(10).toInstant(), mapper.dateTime(raw.getLastSeenAt()).toInstant());
        assertEquals(3, raw.getRawRecordId());
        assertEquals("YOUTH_POLICY", raw.getSource().name());
        assertEquals("p1", raw.getExternalId());
    }

    @Test
    void validatesTimestampRangeAndNanos() {
        for (var value : List.of(com.google.protobuf.Timestamp.newBuilder().setSeconds(-62135596801L).build(),
                com.google.protobuf.Timestamp.newBuilder().setSeconds(253402300800L).build(),
                com.google.protobuf.Timestamp.newBuilder().setNanos(1000000000).build())) {
            assertThrows(IllegalArgumentException.class, () -> mapper.dateTime(value));
        }
        for (long second : new long[]{-62135596800L, 253402300799L}) {
            var value = com.google.protobuf.Timestamp.newBuilder().setSeconds(second).setNanos(999999999).build();
            assertEquals(value, mapper.timestamp(mapper.dateTime(value)));
        }
    }
}
