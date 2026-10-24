package com.team3.backend.client.grpc;

import com.google.protobuf.Timestamp;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.dto.response.Employment24PageFailureResponse;
import com.team3.backend.collection.dto.response.RawRecordResponse;
import com.team3.backend.collection.dto.response.SyncJobResponse;
import com.team3.backend.collection.dto.response.SyncPageFailureResponse;
import com.team3.backend.collection.dto.response.SyncRecordResultResponse;
import com.team3.backend.collection.dto.response.SyncStartResponse;
import com.team3.contracts.collection.v1.RawRecord;
import com.team3.contracts.collection.v1.SaveRawRecordRequest;
import com.team3.contracts.collection.v1.SyncJob;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CollectionProtoMapper {
    private final ObjectMapper json;

    public CollectionProtoMapper(ObjectMapper json) { this.json = json; }

    public SyncStartResponse start(com.team3.contracts.collection.v1.SyncStartResponse value) {
        return new SyncStartResponse(value.getJobId(), value.getSource().name(), value.getStatus().name(), value.getMessage());
    }

    public SyncJobResponse job(SyncJob value) {
        return new SyncJobResponse(value.getJobId(), value.getSource().name(), value.getStatus().name(),
                value.hasStartedAt() ? dateTime(value.getStartedAt()) : null,
                value.hasCompletedAt() ? dateTime(value.getCompletedAt()) : null, null,
                value.getFetchedCount(), value.getSuccessCount(), value.getFailedRecordCount(), value.getPendingCount(),
                value.getFailedPageCount(), value.getNewCount(), value.getUpdatedCount(), value.getUnchangedCount(),
                value.getPageFailuresList().stream().map(f -> new SyncPageFailureResponse(f.getPageNumber(),
                        nullable(f.getErrorCode()), nullable(f.getErrorMessage()), f.getRetryable())).toList(),
                value.getEmployment24PageFailuresList().stream().map(f -> new Employment24PageFailureResponse(
                        json.readTree(f.getRequestJson()), nullable(f.getErrorCode()), nullable(f.getErrorMessage()), f.getRetryable())).toList(),
                value.getIncompleteNcsCodesList(),
                value.getRecordFailuresList().stream().map(f -> new SyncRecordResultResponse(
                        f.getRecordResultId() == 0 ? null : f.getRecordResultId(), nullable(f.getExternalId()),
                        f.hasPageNumber() ? f.getPageNumber() : null, f.hasItemIndex() ? f.getItemIndex() : null,
                        f.hasRawRecordId() ? f.getRawRecordId() : null, f.getStatus(), nullable(f.getResultType()),
                        nullable(f.getErrorCode()), nullable(f.getErrorMessage()),
                        f.hasProcessedAt() ? dateTime(f.getProcessedAt()) : null)).toList(),
                value.hasFailureReason() ? value.getFailureReason() : null);
    }

    public RawRecordResponse raw(RawRecord value) {
        return new RawRecordResponse(value.getRawRecordId(), value.getSource().name(), value.getExternalId(),
                value.getPayloadHash(), dateTime(value.getCollectedAt()), dateTime(value.getLastSeenAt()),
                json.readTree(value.getPayloadJson()));
    }

    public SaveRawRecordRequest save(RawRecordSaveRequest value) {
        if (value == null || value.syncId() == null || value.syncId() < 1 || value.externalId() == null
                || value.externalId().isBlank() || value.payload() == null || value.payload().isNull()
                || value.collectedAt() == null) {
            throw new IllegalArgumentException("Invalid raw record request");
        }
        return SaveRawRecordRequest.newBuilder().setSyncId(value.syncId()).setExternalId(value.externalId())
                .setPayloadJson(json.writeValueAsString(value.payload())).setCollectedAt(timestamp(value.collectedAt())).build();
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

    private String nullable(String value) { return value.isEmpty() ? null : value; }
}
