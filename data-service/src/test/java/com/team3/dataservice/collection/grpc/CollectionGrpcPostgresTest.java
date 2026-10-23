package com.team3.dataservice.collection.grpc;

import com.team3.contracts.collection.v1.*;
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
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real gRPC -> services -> PostgreSQL. Only the asynchronous external fetch worker is replaced. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"spring.grpc.server.port=0", "yon.normalization.condition-set-backfill.enabled=false"})
class CollectionGrpcPostgresTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @Autowired GrpcServerLifecycle lifecycle;
    @Autowired JdbcTemplate db;
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
}
