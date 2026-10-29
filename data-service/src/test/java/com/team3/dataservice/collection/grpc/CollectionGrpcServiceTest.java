package com.team3.dataservice.collection.grpc;

import com.team3.contracts.collection.v1.*;
import com.team3.dataservice.collection.domain.CollectionSource;
import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.dto.response.RawRecordResponse;
import com.team3.dataservice.collection.dto.response.SyncJobResponse;
import com.team3.dataservice.collection.service.*;
import com.team3.dataservice.global.security.InternalKeyServerInterceptor;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionGrpcServiceTest {
    private final ManualSyncService manual = mock(ManualSyncService.class);
    private final SyncJobService jobs = mock(SyncJobService.class);
    private final RawRecordService records = mock(RawRecordService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final CollectionProtoMapper mapper = new CollectionProtoMapper(json);
    private Server server;
    private ManagedChannel channel;
    private CollectionServiceGrpc.CollectionServiceBlockingStub stub;
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-23T14:03:04.123456789+09:00");

    @BeforeEach
    void start() throws Exception {
        var name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(new CollectionGrpcService(manual, jobs, records, mapper),
                        new CollectionExceptionInterceptor(), new InternalKeyServerInterceptor("test-key")))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = authenticated("test-key");
    }

    private CollectionServiceGrpc.CollectionServiceBlockingStub authenticated(String key) {
        var metadata = new Metadata();
        if (key != null) metadata.put(InternalKeyServerInterceptor.INTERNAL_KEY, key);
        return CollectionServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void startsWithRestDefaultsAndExplicitArguments() {
        when(manual.start(eq(CollectionSource.YOUTH_POLICY), anyInt(), anyInt()))
                .thenReturn(new com.team3.dataservice.collection.dto.response.SyncStartResponse(
                        7L, "YOUTH_POLICY", SyncJobStatus.PENDING, "accepted"));
        var request = StartSyncRequest.newBuilder().setSource(com.team3.contracts.collection.v1.CollectionSource.YOUTH_POLICY);
        assertEquals(7, stub.startSync(request.build()).getJobId());
        verify(manual).start(CollectionSource.YOUTH_POLICY, 100, 1000);
        stub.startSync(request.setPageSize(20).setMaxPages(30).build());
        verify(manual).start(CollectionSource.YOUTH_POLICY, 20, 30);
    }

    @Test
    void rejectsUnspecifiedAndUnknownSourceBeforeCallingService() {
        for (int value : new int[]{0, 999}) {
            assertError(() -> stub.startSync(StartSyncRequest.newBuilder().setSourceValue(value).build()),
                    Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        }
        verifyNoInteractions(manual);
    }

    @Test
    void listsKstDateAndReturnsDetailOnlyForSingleJob() {
        var job = fixture();
        when(jobs.listByDate(LocalDate.of(2026, 10, 23))).thenReturn(List.of(job));
        when(jobs.get(7L)).thenReturn(job);
        var list = stub.listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder().setDate("2026-10-23").build());
        assertEquals(1, list.getJobsCount());
        assertEquals(0, list.getJobs(0).getRecordFailuresCount());
        assertEquals(1, list.getJobs(0).getFailedRecordCount());
        var detail = stub.getSyncJob(GetSyncJobRequest.newBuilder().setJobId(7).build());
        assertEquals(mapper.job(job, true), detail);
        assertEquals(1, detail.getRecordFailuresCount());
    }

    @Test
    void rejectsInvalidDate() {
        for (String date : List.of("", "2026-02-30", "not-a-date")) {
            assertError(() -> stub.listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder().setDate(date).build()),
                    Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        }
        verifyNoInteractions(jobs);
    }

    @Test
    void savesAndReadsRawRecordThroughSameServiceAsRest() {
        var payload = json.readTree("{\"plcyNo\":\"p1\",\"large\":900719925474099312345,\"nested\":{\"z\":2,\"a\":1}}");
        var response = new RawRecordResponse(9L, "YOUTH_POLICY", "p1", new RawRecordFingerprint().hash(payload), now, now, payload);
        when(records.save(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, com.team3.dataservice.collection.dto.request.RawRecordSaveRequest.class);
            assertEquals(7L, request.syncId());
            assertEquals("p1", request.externalId());
            assertEquals(now.toInstant(), request.collectedAt().toInstant());
            assertEquals(response.payloadHash(), new RawRecordFingerprint().hash(request.payload()));
            return response;
        });
        when(records.get(9L)).thenReturn(response);
        var saved = stub.saveRawRecord(SaveRawRecordRequest.newBuilder().setSyncId(7).setExternalId("p1")
                .setPayloadJson(payload.toString()).setCollectedAt(mapper.timestamp(now)).build());
        assertEquals(saved, stub.getRawRecord(GetRawRecordRequest.newBuilder().setRawRecordId(9).build()));
        assertEquals(response.payloadHash(), new RawRecordFingerprint().hash(json.readTree(saved.getPayloadJson())));
    }

    @Test
    void rejectsMalformedJsonAndMissingRequiredFields() {
        var request = SaveRawRecordRequest.newBuilder().setSyncId(7).setExternalId("p1").setCollectedAt(mapper.timestamp(now));
        assertError(() -> stub.saveRawRecord(request.setPayloadJson("{").build()), Status.Code.INVALID_ARGUMENT, "COLLECTION_002");
        for (String payload : List.of("", "null")) {
            assertError(() -> stub.saveRawRecord(request.setPayloadJson(payload).build()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        }
        request.setPayloadJson("{}");
        assertError(() -> stub.saveRawRecord(request.setSyncId(0).build()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        assertError(() -> stub.saveRawRecord(request.setSyncId(7).setExternalId(" ").build()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        assertError(() -> stub.saveRawRecord(request.setExternalId("p1").clearCollectedAt().build()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        assertError(() -> stub.saveRawRecord(request.setCollectedAt(com.google.protobuf.Timestamp.newBuilder().setNanos(-1)).build()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        verifyNoInteractions(records);
    }

    @Test
    void rejectsMissingAndWrongKeysWithoutLeakingValues() {
        for (String key : new String[]{null, "wrong-secret"}) {
            var error = assertError(() -> authenticated(key).getSyncJob(GetSyncJobRequest.getDefaultInstance()),
                    Status.Code.UNAUTHENTICATED, "AUTH_008");
            assertFalse(error.getMessage().contains("test-key"));
            assertFalse(error.getMessage().contains("wrong-secret"));
        }
        verifyNoInteractions(jobs);
        assertThrows(IllegalStateException.class, () -> new InternalKeyServerInterceptor(" "));
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new IllegalArgumentException("private details"), Status.Code.INVALID_ARGUMENT, "COLLECTION_003"),
                Arguments.of(new EntityNotFoundException("private details"), Status.Code.NOT_FOUND, "COLLECTION_004"),
                Arguments.of(new IllegalStateException("private details"), Status.Code.FAILED_PRECONDITION, "COLLECTION_005"),
                Arguments.of(new TaskRejectedException("private details"), Status.Code.UNAVAILABLE, "COLLECTION_001"),
                Arguments.of(new DataAccessResourceFailureException("private details"), Status.Code.INTERNAL, "COLLECTION_006"),
                Arguments.of(new RuntimeException("private details"), Status.Code.INTERNAL, null));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void mapsErrorsWithoutInternalDetails(RuntimeException exception, Status.Code code, String errorCode) {
        when(jobs.get(7L)).thenThrow(exception);
        var error = assertError(() -> stub.getSyncJob(GetSyncJobRequest.newBuilder().setJobId(7).build()), code, errorCode);
        assertFalse(error.getMessage().contains("private details"));
    }

    @Test
    void duplicateSyncIsFailedPrecondition() {
        when(manual.start(CollectionSource.YOUTH_POLICY, 100, 1000)).thenThrow(new IllegalStateException("duplicate"));
        assertError(() -> stub.startSync(StartSyncRequest.newBuilder()
                .setSource(com.team3.contracts.collection.v1.CollectionSource.YOUTH_POLICY).build()),
                Status.Code.FAILED_PRECONDITION, "COLLECTION_005");
    }

    @Test
    void missingRawRecordIsNotFound() {
        when(records.get(99L)).thenThrow(new EntityNotFoundException());
        assertError(() -> stub.getRawRecord(GetRawRecordRequest.newBuilder().setRawRecordId(99).build()),
                Status.Code.NOT_FOUND, "COLLECTION_004");
    }

    private StatusRuntimeException assertError(org.junit.jupiter.api.function.Executable call, Status.Code code, String errorCode) {
        var error = assertThrows(StatusRuntimeException.class, call);
        assertEquals(code, error.getStatus().getCode());
        assertEquals(errorCode, error.getTrailers().get(CollectionExceptionInterceptor.ERROR_CODE));
        return error;
    }

    private SyncJobResponse fixture() {
        var failure = new com.team3.dataservice.collection.dto.response.SyncRecordResultResponse(null, "p1", null, 3, null,
                com.team3.dataservice.collection.domain.RecordProcessStatus.FAILED, null, "RECORD_001", "failed", now);
        return new SyncJobResponse(7L, "YOUTH_POLICY", SyncJobStatus.PARTIAL_SUCCESS, now, now.plusMinutes(1), null,
                10, 8, 1, 1, 2, 3, 2, 3, List.of(), List.of(), List.of("01"), List.of(failure), "partial");
    }
}
