package com.team3.dataservice.collection.grpc;

import com.team3.contracts.collection.v1.*;
import com.team3.dataservice.collection.domain.CollectionSource;
import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.dto.response.RawRecordResponse;
import com.team3.dataservice.collection.dto.response.SyncJobResponse;
import com.team3.dataservice.collection.dto.response.SyncStartResponse;
import com.team3.dataservice.collection.service.ManualSyncService;
import com.team3.dataservice.collection.service.RawRecordService;
import com.team3.dataservice.collection.service.SyncJobService;
import com.team3.dataservice.global.security.InternalKeyServerInterceptor;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Checks non-web Boot startup, all five RPCs and global interceptors over Netty. */
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.grpc.server.port=0")
class CollectionGrpcTransportTest {
    @Autowired GrpcServerLifecycle lifecycle;
    @Autowired CollectionProtoMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoBean ManualSyncService manual;
    @MockitoBean SyncJobService jobs;
    @MockitoBean RawRecordService records;

    @Test
    void grpcOnlyServerExposesServicesAndErrors() throws Exception {
        assertFalse(context.getClass().getName().contains("Web"));
        assertFalse(context.containsBean("dispatcherServlet"));
        assertNotNull(context.getBean(org.springframework.web.client.RestClient.Builder.class));
        var now = OffsetDateTime.parse("2026-10-23T10:00:00+09:00");
        var start = new SyncStartResponse(7L, "YOUTH_POLICY", SyncJobStatus.PENDING, "accepted");
        var job = new SyncJobResponse(7L, "YOUTH_POLICY", SyncJobStatus.PENDING, now, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), null);
        var raw = new RawRecordResponse(9L, "YOUTH_POLICY", "p1", "hash", now, now, json.readTree("{\"a\":1}"));
        when(manual.start(CollectionSource.YOUTH_POLICY, 100, 1000)).thenReturn(start);
        when(jobs.get(7L)).thenReturn(job);
        when(jobs.listByDate(LocalDate.of(2026, 10, 23))).thenReturn(List.of(job));
        when(records.get(9L)).thenReturn(raw);
        when(records.save(any())).thenReturn(raw);
        var channel = Grpc.newChannelBuilderForAddress("localhost", lifecycle.getPort(), InsecureChannelCredentials.create()).build();
        try {
            var metadata = new Metadata();
            metadata.put(InternalKeyServerInterceptor.INTERNAL_KEY, "test-internal-api-key");
            var stub = CollectionServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata)).withDeadlineAfter(5, TimeUnit.SECONDS);
            assertEquals(mapper.start(start), stub.startSync(StartSyncRequest.newBuilder()
                    .setSource(com.team3.contracts.collection.v1.CollectionSource.YOUTH_POLICY).build()));
            assertEquals(mapper.job(job, true), stub.getSyncJob(GetSyncJobRequest.newBuilder().setJobId(7).build()));
            assertEquals(mapper.job(job, false), stub.listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder()
                    .setDate("2026-10-23").build()).getJobs(0));
            assertEquals(mapper.raw(raw), stub.getRawRecord(GetRawRecordRequest.newBuilder().setRawRecordId(9).build()));
            assertEquals(mapper.raw(raw), stub.saveRawRecord(SaveRawRecordRequest.newBuilder().setSyncId(7)
                    .setExternalId("p1").setCollectedAt(mapper.timestamp(now)).setPayloadJson("{\"a\":1}").build()));
            var denied = assertThrows(StatusRuntimeException.class, () -> CollectionServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS).getSyncJob(GetSyncJobRequest.getDefaultInstance()));
            assertEquals(Status.Code.UNAUTHENTICATED, denied.getStatus().getCode());
            when(jobs.get(7L)).thenThrow(new IllegalStateException("sensitive internal state"));
            var error = assertThrows(StatusRuntimeException.class, () -> stub.getSyncJob(GetSyncJobRequest.newBuilder().setJobId(7).build()));
            assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
            assertEquals("COLLECTION_005", error.getTrailers().get(CollectionExceptionInterceptor.ERROR_CODE));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
