package com.team3.dataservice.collection.grpc;

import org.junit.jupiter.api.Tag;

import com.team3.contracts.collection.v1.*;
import com.team3.dataservice.collection.domain.entity.ExternalSource;
import com.team3.dataservice.collection.domain.entity.SyncHistory;
import com.team3.dataservice.collection.domain.repository.ExternalSourceRepository;
import com.team3.dataservice.collection.domain.repository.SyncHistoryRepository;
import com.team3.dataservice.collection.domain.RecordResultType;
import com.team3.dataservice.collection.dto.response.SyncPageFailureResponse;
import com.team3.dataservice.collection.service.ManualSyncWorker;
import com.team3.dataservice.collection.service.RawRecordFingerprint;
import com.team3.dataservice.collection.service.SyncJobService;
import com.team3.dataservice.global.security.InternalKeyServerInterceptor;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.team3.dataservice.support.ApplicationPostgres;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real gRPC -> services -> PostgreSQL. Only the asynchronous external fetch worker is replaced. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"spring.grpc.server.port=0", "yon.normalization.condition-set-backfill.enabled=false"})
@Tag("postgres")
class CollectionGrpcPostgresTest {
    @Container static final PostgreSQLContainer postgres = ApplicationPostgres.create();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        ApplicationPostgres.register(properties, postgres);
    }
    @Autowired GrpcServerLifecycle lifecycle;
    @Autowired JdbcTemplate db;
    @Autowired ExternalSourceRepository sources;
    @Autowired SyncHistoryRepository histories;
    @Autowired SyncJobService jobs;
    @Autowired CollectionProtoMapper mapper;
    @Autowired RawRecordFingerprint fingerprint;
    @Autowired ObjectMapper json;
    @MockitoBean ManualSyncWorker worker;
    private ManagedChannel channel;
    private CollectionServiceGrpc.CollectionServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        db.execute("TRUNCATE raw_normalization_request, raw_external_record, sync_history, external_source RESTART IDENTITY CASCADE");
        channel = Grpc.newChannelBuilderForAddress("localhost", lifecycle.getPort(), InsecureChannelCredentials.create()).build();
        var headers = new Metadata(); headers.put(InternalKeyServerInterceptor.INTERNAL_KEY, "test-internal-api-key");
        stub = CollectionServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    @AfterEach
    void disconnect() throws Exception { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }

    private CollectionServiceGrpc.CollectionServiceBlockingStub client() { return stub.withDeadlineAfter(5, TimeUnit.SECONDS); }
    private long start() {
        return client().startSync(StartSyncRequest.newBuilder().setSource(CollectionSource.YOUTH_POLICY).build()).getJobId();
    }
    private SaveRawRecordRequest raw(long id, String payload, OffsetDateTime at) {
        return SaveRawRecordRequest.newBuilder().setSyncId(id).setExternalId("p1")
                .setPayloadJson(payload).setCollectedAt(mapper.timestamp(at)).build();
    }
    private void error(org.junit.jupiter.api.function.Executable action, Status.Code status, String code) {
        var exception = assertThrows(StatusRuntimeException.class, action);
        assertEquals(status, exception.getStatus().getCode());
        assertEquals(code, exception.getTrailers().get(CollectionExceptionInterceptor.ERROR_CODE));
    }

    @Test
    void startsListsAndReadsPersistedJobAndRejectsDuplicate() {
        long id = start();
        assertEquals(1, db.queryForObject("SELECT count(*) FROM sync_history", Integer.class));
        assertEquals(SyncJobStatus.PENDING, client().getSyncJob(GetSyncJobRequest.newBuilder().setJobId(id).build()).getStatus());
        var list = client().listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder()
                .setDate(LocalDate.now(ZoneId.of("Asia/Seoul")).toString()).build());
        assertTrue(list.getJobsList().stream().anyMatch(job -> job.getJobId() == id));
        error(this::start, Status.Code.FAILED_PRECONDITION, "COLLECTION_005");
        assertEquals(1, db.queryForObject("SELECT count(*) FROM sync_history", Integer.class));
    }

    @Test
    void rawSaveIsIdempotentAndChangedPayloadCreatesSnapshot() {
        long id = start(); jobs.start(id);
        var at = OffsetDateTime.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var payload = "{\"z\":900719925474099312345,\"a\":1}";
        var first = client().saveRawRecord(raw(id, payload, at));
        var same = client().saveRawRecord(raw(id, "{\"a\":1,\"z\":900719925474099312345}", at.plusSeconds(1)));
        assertEquals(first.getRawRecordId(), same.getRawRecordId());
        assertEquals(fingerprint.hash(json.readTree(payload)), same.getPayloadHash());
        assertEquals(first.getCollectedAt(), same.getCollectedAt());
        assertEquals(mapper.timestamp(at.plusSeconds(1)), same.getLastSeenAt());
        var changed = client().saveRawRecord(raw(id, "{\"a\":2}", at.plusSeconds(2)));
        assertNotEquals(first.getRawRecordId(), changed.getRawRecordId());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM raw_external_record", Integer.class));
        assertEquals(json.readTree(payload), json.readTree(client().getRawRecord(
                GetRawRecordRequest.newBuilder().setRawRecordId(first.getRawRecordId()).build()).getPayloadJson()));
    }

    @Test
    void retryRpcValidatesTargetAndReturnsANewPendingJob() {
        var source = sources.save(new ExternalSource("YOUTH_POLICY", "youth", "https://example.test", true, OffsetDateTime.now()));
        var failed = history(source, SyncJobStatus.FAILED, OffsetDateTime.now().minusMinutes(1));
        var retry = client().retrySync(RetrySyncRequest.newBuilder().setJobId(failed.getId()).build());
        assertNotEquals(failed.getId(), retry.getJobId());
        assertEquals(SyncJobStatus.PENDING, retry.getStatus());
        assertEquals("MANUAL_RETRY", histories.findById(retry.getJobId()).orElseThrow().getSyncType());
        error(() -> client().retrySync(RetrySyncRequest.newBuilder().setJobId(failed.getId()).build()),
                Status.Code.FAILED_PRECONDITION, "COLLECTION_005");
        error(() -> client().retrySync(RetrySyncRequest.newBuilder().setJobId(999999).build()),
                Status.Code.NOT_FOUND, "COLLECTION_004");
        error(() -> client().retrySync(RetrySyncRequest.getDefaultInstance()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
    }

    @Test
    void rejectsStaleObservationWithoutChangingStoredSnapshot() {
        long id = start(); jobs.start(id);
        var at = OffsetDateTime.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var first = client().saveRawRecord(raw(id, "{\"a\":1}", at));
        error(() -> client().saveRawRecord(raw(id, "{\"a\":2}", at.minusDays(1))), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        assertEquals(1, db.queryForObject("SELECT count(*) FROM raw_external_record", Integer.class));
        assertEquals(first, client().getRawRecord(GetRawRecordRequest.newBuilder().setRawRecordId(first.getRawRecordId()).build()));
    }

    @Test
    void rejectsInvalidStateMissingRecordsAndInvalidDates() {
        long id = start();
        error(() -> client().saveRawRecord(raw(id, "{}", OffsetDateTime.now().plusSeconds(1))), Status.Code.FAILED_PRECONDITION, "COLLECTION_005");
        error(() -> client().getSyncJob(GetSyncJobRequest.newBuilder().setJobId(999).build()), Status.Code.NOT_FOUND, "COLLECTION_004");
        error(() -> client().getRawRecord(GetRawRecordRequest.newBuilder().setRawRecordId(999).build()), Status.Code.NOT_FOUND, "COLLECTION_004");
        for (String date : new String[]{"2026-02-30", "0000-01-01", "+10000-01-01"}) {
            error(() -> client().listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder().setDate(date).build()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM raw_external_record", Integer.class));
    }

    @Test
    void unauthenticatedCallCannotCreateJob() {
        error(() -> CollectionServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .startSync(StartSyncRequest.newBuilder().setSource(CollectionSource.YOUTH_POLICY).build()), Status.Code.UNAUTHENTICATED, "AUTH_008");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM sync_history", Integer.class));
    }
    @Test
    void listsEveryStatusByKoreanDateWithStableNewestFirstOrder() {
        var from = OffsetDateTime.parse("2026-10-20T15:00:00Z");
        var until = from.plusDays(1);
        var youth = sources.save(new ExternalSource(
                "YOUTH_POLICY", "youth", "https://example.test", true, from));
        var employment = sources.save(new ExternalSource(
                "EMPLOYMENT24", "employment", "https://example.test", true, from));
        history(youth, SyncJobStatus.FAILED, from.minusNanos(1000));
        var success = history(youth, SyncJobStatus.SUCCESS, from);
        var pending = history(youth, SyncJobStatus.PENDING, from.plusHours(1));
        var running = history(youth, SyncJobStatus.RUNNING, from.plusHours(2));
        var partial = history(youth, SyncJobStatus.PARTIAL_SUCCESS, from.plusHours(3));
        var failed = history(employment, SyncJobStatus.FAILED, until.minusNanos(1000));
        var interrupted = history(youth, SyncJobStatus.INTERRUPTED, until.minusNanos(1000));
        history(youth, SyncJobStatus.SUCCESS, until);
        var result = client().listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder().setDate("2026-10-21").build());
        assertEquals(java.util.List.of(interrupted.getId(), failed.getId(), partial.getId(), running.getId(), pending.getId(), success.getId()),
                result.getJobsList().stream().map(SyncJob::getJobId).toList());
        assertEquals(java.util.List.of("INTERRUPTED", "FAILED", "PARTIAL_SUCCESS", "RUNNING", "PENDING", "SUCCESS"),
                result.getJobsList().stream().map(job -> job.getStatus().name()).toList());
        assertEquals(CollectionSource.EMPLOYMENT24, result.getJobs(1).getSource());
        assertEquals("test failure", result.getJobs(1).getFailureReason());
        assertEquals(1, result.getJobs(2).getFailedPageCount());
        assertEquals("HTTP_500", result.getJobs(2).getPageFailures(0).getErrorCode());
        assertFalse(result.getJobs(3).hasCompletedAt());
    }

    private SyncHistory history(
            ExternalSource source,
            SyncJobStatus status, OffsetDateTime at) {
        var value = SyncHistory.pending(source, "SCHEDULED", at);
        switch (status) {
            case RUNNING -> value.start();
            case SUCCESS, PARTIAL_SUCCESS -> {
                value.start();
                if (status == SyncJobStatus.PARTIAL_SUCCESS) {
                    value.recordSuccess(RecordResultType.CREATED);
                    value.recordCollection(1, java.util.List.of(new SyncPageFailureResponse(
                            2, "HTTP_500", "upstream error", true)), java.util.List.of(), java.util.List.of());
                }
                value.finish(status == SyncJobStatus.SUCCESS, at.plusMinutes(1));
            }
            case FAILED, INTERRUPTED -> value.fail("test failure",
                    status == SyncJobStatus.INTERRUPTED, at.plusMinutes(1));
            default -> { }
        }
        return histories.save(value);
    }

    @Test
    void returnsEmptyHistoryForDateWithoutJobs() {
        assertEquals(0, client().listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder().setDate("2030-01-01").build()).getJobsCount());
    }

    @Test
    void invalidSourceAndLimitsDoNotCreateHistory() {
        for (int source : new int[]{0, 999}) {
            error(() -> client().startSync(StartSyncRequest.newBuilder().setSourceValue(source).build()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        }
        for (int size : new int[]{-1, 101}) {
            error(() -> client().startSync(StartSyncRequest.newBuilder().setSource(CollectionSource.YOUTH_POLICY).setPageSize(size).build()),
                    Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        }
        error(() -> client().startSync(StartSyncRequest.newBuilder().setSource(CollectionSource.YOUTH_POLICY).setMaxPages(1001).build()),
                Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        error(() -> client().getSyncJob(GetSyncJobRequest.getDefaultInstance()), Status.Code.INVALID_ARGUMENT, "COLLECTION_003");
        assertEquals(0, histories.count());
    }

    @Test
    void preservesAllSnapshotsWhenContentChangesBack() {
        long id = start(); jobs.start(id);
        var at = OffsetDateTime.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var first = client().saveRawRecord(raw(id, "{\"value\":\"A\"}", at));
        var second = client().saveRawRecord(raw(id, "{\"value\":\"B\"}", at.plusSeconds(1)));
        var third = client().saveRawRecord(raw(id, "{\"value\":\"A\"}", at.plusSeconds(2)));
        assertEquals(3, java.util.Set.of(first.getRawRecordId(), second.getRawRecordId(), third.getRawRecordId()).size());
        assertEquals(first.getPayloadHash(), third.getPayloadHash());
        assertEquals(3, db.queryForObject("SELECT count(*) FROM raw_external_record", Integer.class));
        for (var record : java.util.List.of(first, second, third)) {
            assertEquals(record, client().getRawRecord(GetRawRecordRequest.newBuilder().setRawRecordId(record.getRawRecordId()).build()));
        }
    }

    @Test
    void concurrentFirstObservationsAcrossExecutionsProduceOneSnapshot() throws Exception {
        var at = OffsetDateTime.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var source = sources.save(new ExternalSource(
                "YOUTH_POLICY", "youth", "https://example.test", true, at.minusSeconds(2)));
        long firstId = histories.save(new SyncHistory(source, "FULL", at.minusSeconds(1))).getId();
        long secondId = histories.save(new SyncHistory(source, "FULL", at.minusSeconds(1))).getId();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return client().saveRawRecord(raw(firstId, "{\"value\":\"A\"}", at)).getRawRecordId(); });
            var second = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return client().saveRawRecord(raw(secondId, "{\"value\":\"A\"}", at)).getRawRecordId(); });
            assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM raw_external_record", Integer.class));
    }

}
