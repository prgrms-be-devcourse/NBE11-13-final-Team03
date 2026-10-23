package com.team3.dataservice.collection.grpc;

import com.google.protobuf.Timestamp;
import com.team3.contracts.collection.v1.CollectionSource;
import com.team3.contracts.collection.v1.Employment24PageFailure;
import com.team3.contracts.collection.v1.PageFailure;
import com.team3.contracts.collection.v1.RawRecord;
import com.team3.contracts.collection.v1.RecordFailure;
import com.team3.contracts.collection.v1.SaveRawRecordRequest;
import com.team3.contracts.collection.v1.SyncJob;
import com.team3.contracts.collection.v1.SyncJobStatus;
import com.team3.dataservice.collection.dto.request.RawRecordSaveRequest;
import com.team3.dataservice.collection.dto.response.RawRecordResponse;
import com.team3.dataservice.collection.dto.response.SyncJobResponse;
import com.team3.dataservice.collection.dto.response.SyncRecordResultResponse;
import com.team3.dataservice.collection.dto.response.SyncStartResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CollectionProtoMapper {
    private final ObjectMapper json;

    public CollectionProtoMapper(ObjectMapper json) {
        this.json = json;
    }

    public com.team3.contracts.collection.v1.SyncStartResponse start(SyncStartResponse value) {
        return com.team3.contracts.collection.v1.SyncStartResponse.newBuilder()
                .setJobId(value.jobId()).setSource(CollectionSource.valueOf(value.source()))
                .setStatus(SyncJobStatus.valueOf(value.status().name())).setMessage(value.message()).build();
    }

    public SyncJob job(SyncJobResponse value, boolean includeRecordFailures) {
        var result = SyncJob.newBuilder().setJobId(value.jobId())
                .setSource(CollectionSource.valueOf(value.source()))
                .setStatus(SyncJobStatus.valueOf(value.status().name()))
                .setStartedAt(timestamp(value.startedAt()))
                .setFetchedCount(value.fetchedCount()).setSuccessCount(value.successCount())
                .setFailedRecordCount(value.failedRecordCount()).setPendingCount(value.pendingCount())
                .setFailedPageCount(value.failedPageCount()).setNewCount(value.newCount())
                .setUpdatedCount(value.updatedCount()).setUnchangedCount(value.unchangedCount())
                .addAllIncompleteNcsCodes(value.incompleteNcsCodes());
        if (value.completedAt() != null) result.setCompletedAt(timestamp(value.completedAt()));
        if (value.failureReason() != null) result.setFailureReason(value.failureReason());
        value.pageFailures().forEach(failure -> result.addPageFailures(PageFailure.newBuilder()
                .setPageNumber(failure.pageNumber()).setErrorCode(text(failure.errorCode()))
                .setErrorMessage(text(failure.errorMessage())).setRetryable(failure.retryable())));
        value.employment24PageFailures().forEach(failure -> result.addEmployment24PageFailures(
                Employment24PageFailure.newBuilder().setRequestJson(json.writeValueAsString(failure.request()))
                        .setErrorCode(text(failure.errorCode())).setErrorMessage(text(failure.errorMessage()))
                        .setRetryable(failure.retryable())));
        if (includeRecordFailures) value.recordFailures().forEach(failure -> result.addRecordFailures(record(failure)));
        return result.build();
    }

    private RecordFailure record(SyncRecordResultResponse value) {
        var result = RecordFailure.newBuilder().setExternalId(text(value.externalId()))
                .setStatus(value.status().name()).setResultType(value.resultType() == null ? "" : value.resultType().name())
                .setErrorCode(text(value.errorCode())).setErrorMessage(text(value.errorMessage()));
        // Collection failures created before persistence have no record result ID.
        if (value.recordResultId() != null) result.setRecordResultId(value.recordResultId());
        if (value.pageNumber() != null) result.setPageNumber(value.pageNumber());
        if (value.itemIndex() != null) result.setItemIndex(value.itemIndex());
        if (value.rawRecordId() != null) result.setRawRecordId(value.rawRecordId());
        if (value.processedAt() != null) result.setProcessedAt(timestamp(value.processedAt()));
        return result.build();
    }

    public RawRecord raw(RawRecordResponse value) {
        return RawRecord.newBuilder().setRawRecordId(value.rawRecordId())
                .setSource(CollectionSource.valueOf(value.source())).setExternalId(value.externalId())
                .setPayloadHash(value.payloadHash()).setCollectedAt(timestamp(value.collectedAt()))
                .setLastSeenAt(timestamp(value.lastSeenAt())).setPayloadJson(json.writeValueAsString(value.payload())).build();
    }

    public RawRecordSaveRequest save(SaveRawRecordRequest value) {
        if (!value.hasCollectedAt()) throw new IllegalArgumentException("collected_at is required");
        var payload = json.readTree(value.getPayloadJson());
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            throw new IllegalArgumentException("payload_json is required");
        }
        return new RawRecordSaveRequest(value.getSyncId(), value.getExternalId(), payload, dateTime(value.getCollectedAt()));
    }

    public Timestamp timestamp(OffsetDateTime value) {
        var instant = value.toInstant();
        if (instant.getEpochSecond() < -62135596800L || instant.getEpochSecond() > 253402300799L) {
            throw new IllegalArgumentException("Timestamp out of range");
        }
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    public OffsetDateTime dateTime(Timestamp value) {
        if (value.getSeconds() < -62135596800L || value.getSeconds() > 253402300799L
                || value.getNanos() < 0 || value.getNanos() > 999999999) {
            throw new IllegalArgumentException("Invalid timestamp");
        }
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos()).atOffset(ZoneOffset.UTC);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
