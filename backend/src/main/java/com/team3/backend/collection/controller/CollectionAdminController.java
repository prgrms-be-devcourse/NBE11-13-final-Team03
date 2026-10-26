package com.team3.backend.collection.controller;

import com.team3.backend.client.grpc.CollectionProtoMapper;
import com.team3.backend.client.grpc.GrpcStatusTranslator;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.dto.response.RawRecordResponse;
import com.team3.backend.collection.dto.response.SyncJobResponse;
import com.team3.backend.collection.dto.response.SyncStartResponse;
import com.team3.backend.collection.exception.CollectionErrorCode;
import com.team3.backend.global.exception.BusinessException;
import com.team3.contracts.collection.v1.CollectionServiceGrpc;
import com.team3.contracts.collection.v1.CollectionSource;
import com.team3.contracts.collection.v1.GetRawRecordRequest;
import com.team3.contracts.collection.v1.GetSyncJobRequest;
import com.team3.contracts.collection.v1.ListSyncJobsByDateRequest;
import com.team3.contracts.collection.v1.StartSyncRequest;
import com.team3.contracts.collection.v1.RetrySyncRequest;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Public ADMIN API; collection execution remains in data-service. */
@RestController
@RequestMapping("/api/admin/collection")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
public class CollectionAdminController {
    private final CollectionServiceGrpc.CollectionServiceBlockingStub collectionStub;
    private final CollectionProtoMapper mapper;
    private final GrpcStatusTranslator errors;
    private final ObjectMapper json;

    @PostMapping("/syncs/{source}")
    public ResponseEntity<SyncStartResponse> startSync(@PathVariable String source,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String maxPages) {
        var request = argument(() -> {
            var type = CollectionSource.valueOf(source);
            if (type == CollectionSource.COLLECTION_SOURCE_UNSPECIFIED || type == CollectionSource.UNRECOGNIZED) {
                throw new IllegalArgumentException("Invalid source");
            }
            return StartSyncRequest.newBuilder().setSource(type)
                    .setPageSize(page(pageSize, 100)).setMaxPages(page(maxPages, 1000)).build();
        });
        var response = mapper.start(call(() -> collectionStub.withDeadlineAfter(3, TimeUnit.SECONDS).startSync(request)));
        return ResponseEntity.accepted().location(URI.create("/api/admin/collection/syncs/" + response.jobId())).body(response);
    }

    @GetMapping("/syncs")
    public List<SyncJobResponse> listSyncs(@RequestParam(required = false) String date) {
        argument(() -> {
            if (date == null) throw new IllegalArgumentException("date is required");
            try {
                var parsed = LocalDate.parse(date);
                if (parsed.getYear() < 1 || parsed.getYear() > 9999) throw new IllegalArgumentException("Invalid year");
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Invalid date");
            }
            return date;
        });
        var response = call(() -> collectionStub.withDeadlineAfter(10, TimeUnit.SECONDS).listSyncJobsByDate(
                ListSyncJobsByDateRequest.newBuilder().setDate(date).build()));
        return response.getJobsList().stream().map(mapper::job).toList();
    }

    @PostMapping("/syncs/{jobId}/retry")
    public ResponseEntity<SyncStartResponse> retrySync(@PathVariable String jobId) {
        var request = argument(() -> RetrySyncRequest.newBuilder().setJobId(id(jobId)).build());
        var response = mapper.start(call(() -> collectionStub.withDeadlineAfter(3, TimeUnit.SECONDS).retrySync(request)));
        return ResponseEntity.accepted().location(URI.create("/api/admin/collection/syncs/" + response.jobId())).body(response);
    }

    @GetMapping("/syncs/{jobId}")
    public SyncJobResponse getSync(@PathVariable String jobId) {
        var request = argument(() -> GetSyncJobRequest.newBuilder().setJobId(id(jobId)).build());
        return mapper.job(call(() -> collectionStub.withDeadlineAfter(10, TimeUnit.SECONDS).getSyncJob(request)));
    }

    @PostMapping("/raw-records")
    public RawRecordResponse saveRawRecord(@RequestBody(required = false) String body) {
        RawRecordSaveRequest value;
        try {
            if (body == null || body.isBlank()) throw new BusinessException(CollectionErrorCode.INVALID_BODY);
            value = json.readValue(body, RawRecordSaveRequest.class);
        } catch (JacksonException exception) {
            throw new BusinessException(CollectionErrorCode.INVALID_BODY);
        }
        var request = argument(() -> mapper.save(value));
        return mapper.raw(call(() -> collectionStub.withDeadlineAfter(10, TimeUnit.SECONDS).saveRawRecord(request)));
    }

    @GetMapping("/raw-records/{rawRecordId}")
    public RawRecordResponse getRawRecord(@PathVariable String rawRecordId) {
        var request = argument(() -> GetRawRecordRequest.newBuilder().setRawRecordId(id(rawRecordId)).build());
        return mapper.raw(call(() -> collectionStub.withDeadlineAfter(10, TimeUnit.SECONDS).getRawRecord(request)));
    }

    private long id(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) throw new IllegalArgumentException("ID must be positive");
        return parsed;
    }

    private int page(String value, int max) {
        if (value == null || value.isEmpty()) return 0;
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > max) throw new IllegalArgumentException("Invalid page limit");
        return parsed;
    }

    private <T> T argument(Supplier<T> action) {
        try { return action.get(); }
        catch (IllegalArgumentException exception) { throw new BusinessException(CollectionErrorCode.INVALID_ARGUMENT); }
    }

    private <T> T call(Supplier<T> action) {
        try { return action.get(); }
        catch (StatusRuntimeException exception) { throw errors.translate(exception); }
    }
}
