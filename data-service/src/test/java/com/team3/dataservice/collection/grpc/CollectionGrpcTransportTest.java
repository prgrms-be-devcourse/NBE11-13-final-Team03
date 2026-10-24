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
import com.team3.dataservice.global.security.InternalApiKeyFilter;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Checks Boot registration, Netty transport, global interceptors and REST coexistence. */
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.grpc.server.port=0")
class CollectionGrpcTransportTest {
    @Autowired GrpcServerLifecycle lifecycle;
    @Autowired WebApplicationContext context;
    @Autowired InternalApiKeyFilter filter;
    @Autowired CollectionProtoMapper mapper;
    @Autowired ObjectMapper json;
    @MockitoBean ManualSyncService manual;
    @MockitoBean SyncJobService jobs;
    @MockitoBean RawRecordService records;

    @Test
    void restAndGrpcExposeTheSameServicesAndErrors() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
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
            mvc.perform(post("/internal/collection/syncs/YOUTH_POLICY").header("X-INTERNAL-KEY", "test-internal-api-key"))
                    .andExpect(status().isAccepted()).andExpect(header().string("Location", "/internal/collection/syncs/7"))
                    .andExpect(content().json(json.writeValueAsString(start)));
            assertEquals(mapper.job(job, true), stub.getSyncJob(GetSyncJobRequest.newBuilder().setJobId(7).build()));
            mvc.perform(get("/internal/collection/syncs/7").header("X-INTERNAL-KEY", "test-internal-api-key"))
                    .andExpect(status().isOk()).andExpect(content().json(json.writeValueAsString(job)));
            assertEquals(mapper.job(job, false), stub.listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder()
                    .setDate("2026-10-23").build()).getJobs(0));
            mvc.perform(get("/internal/collection/syncs").param("date", "2026-10-23").header("X-INTERNAL-KEY", "test-internal-api-key"))
                    .andExpect(status().isOk()).andExpect(content().json(json.writeValueAsString(List.of(job))));
            assertEquals(mapper.raw(raw), stub.getRawRecord(GetRawRecordRequest.newBuilder().setRawRecordId(9).build()));
            mvc.perform(get("/internal/collection/raw-records/9").header("X-INTERNAL-KEY", "test-internal-api-key"))
                    .andExpect(status().isOk()).andExpect(content().json(json.writeValueAsString(raw)));
            assertEquals(mapper.raw(raw), stub.saveRawRecord(SaveRawRecordRequest.newBuilder().setSyncId(7)
                    .setExternalId("p1").setCollectedAt(mapper.timestamp(now)).setPayloadJson("{\"a\":1}").build()));
            mvc.perform(post("/internal/collection/raw-records").header("X-INTERNAL-KEY", "test-internal-api-key")
                    .contentType("application/json").content(json.writeValueAsString(
                            new com.team3.dataservice.collection.dto.request.RawRecordSaveRequest(7L, "p1", raw.payload(), now))))
                    .andExpect(status().isOk()).andExpect(content().json(json.writeValueAsString(raw)));
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
    @Test
    void configuredInboundLimitAcceptsRequestLargerThanDefaultFourMiB() throws Exception {
        var now = OffsetDateTime.now();
        when(records.save(any())).thenReturn(new RawRecordResponse(9L, "YOUTH_POLICY", "large", "hash",
                now, now, json.readTree("{}")));
        var channel = Grpc.newChannelBuilderForAddress("localhost", lifecycle.getPort(), InsecureChannelCredentials.create()).build();
        try {
            var headers = new Metadata();
            headers.put(InternalKeyServerInterceptor.INTERNAL_KEY, "test-internal-api-key");
            var stub = CollectionServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)).withDeadlineAfter(5, TimeUnit.SECONDS);
            var payload = "{\"padding\":\"" + "x".repeat(5 * 1024 * 1024) + "\"}";
            var response = stub.saveRawRecord(SaveRawRecordRequest.newBuilder().setSyncId(7).setExternalId("large")
                    .setCollectedAt(mapper.timestamp(now)).setPayloadJson(payload).build());
            assertEquals(9, response.getRawRecordId());
            verify(records).save(argThat(request -> request.payload().path("padding").asString().length() == 5 * 1024 * 1024));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

}
