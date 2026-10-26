package com.team3.backend.collection.controller;

import com.team3.contracts.collection.v1.*;
import com.google.protobuf.Timestamp;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.scheduling.enabled=false")
@AutoConfigureMockMvc
class CollectionAdminControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CollectionServiceGrpc.CollectionServiceBlockingStub stub;

    @BeforeEach
    void deadlines() { when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub); }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void startsWithAcceptedStatusAndAdminLocation() throws Exception {
        when(stub.startSync(any())).thenReturn(SyncStartResponse.newBuilder().setJobId(7)
                .setSource(CollectionSource.YOUTH_POLICY).setStatus(SyncJobStatus.PENDING).setMessage("accepted").build());
        mvc.perform(post("/api/admin/collection/syncs/YOUTH_POLICY").with(csrf()).param("pageSize", "50"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/admin/collection/syncs/7"))
                .andExpect(jsonPath("$.jobId").value(7)).andExpect(jsonPath("$.source").value("YOUTH_POLICY"))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.message").value("accepted"));
        verify(stub).startSync(StartSyncRequest.newBuilder().setSource(CollectionSource.YOUTH_POLICY).setPageSize(50).build());
        verify(stub).withDeadlineAfter(3, TimeUnit.SECONDS);
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void retryReturnsANewAcceptedJobAndUsesTheOriginalIdOnlyAsRetryTarget() throws Exception {
        when(stub.retrySync(any())).thenReturn(SyncStartResponse.newBuilder().setJobId(8)
                .setSource(CollectionSource.YOUTH_POLICY).setStatus(SyncJobStatus.PENDING).setMessage("accepted").build());
        mvc.perform(post("/api/admin/collection/syncs/7/retry").with(csrf()))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/admin/collection/syncs/8"))
                .andExpect(jsonPath("$.jobId").value(8));
        verify(stub).retrySync(RetrySyncRequest.newBuilder().setJobId(7).build());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void readsListAndDetailWithLegacyFieldsIncludingNullTotalCount() throws Exception {
        var job = SyncJob.newBuilder().setJobId(7).setSource(CollectionSource.YOUTH_POLICY).setStatus(SyncJobStatus.RUNNING)
                .setStartedAt(Timestamp.newBuilder().setSeconds(1792713600)).setFetchedCount(10).setSuccessCount(5)
                .setFailedRecordCount(2).setPendingCount(3).setFailedPageCount(1).setNewCount(2).setUpdatedCount(1)
                .setUnchangedCount(2).build();
        when(stub.getSyncJob(any())).thenReturn(job);
        when(stub.listSyncJobsByDate(any())).thenReturn(ListSyncJobsResponse.newBuilder().addJobs(job).build());
        mvc.perform(get("/api/admin/collection/syncs/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(7)).andExpect(jsonPath("$.totalCount").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.completedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fetchedCount").value(10)).andExpect(jsonPath("$.successCount").value(5))
                .andExpect(jsonPath("$.failedRecordCount").value(2)).andExpect(jsonPath("$.pendingCount").value(3))
                .andExpect(jsonPath("$.failedPageCount").value(1)).andExpect(jsonPath("$.newCount").value(2))
                .andExpect(jsonPath("$.updatedCount").value(1)).andExpect(jsonPath("$.unchangedCount").value(2))
                .andExpect(jsonPath("$.pageFailures").isArray()).andExpect(jsonPath("$.employment24PageFailures").isArray())
                .andExpect(jsonPath("$.recordFailures").isArray()).andExpect(jsonPath("$.incompleteNcsCodes").isArray());
        mvc.perform(get("/api/admin/collection/syncs").param("date", "2026-10-23"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].jobId").value(7))
                .andExpect(jsonPath("$[0].totalCount").value(org.hamcrest.Matchers.nullValue()));
        verify(stub).getSyncJob(GetSyncJobRequest.newBuilder().setJobId(7).build());
        verify(stub).listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder().setDate("2026-10-23").build());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void savesAndGetsRawRecordPreservingPayloadAndIgnoringClientHash() throws Exception {
        var time = Timestamp.newBuilder().setSeconds(1792713600).build();
        var raw = RawRecord.newBuilder().setRawRecordId(9).setSource(CollectionSource.YOUTH_POLICY).setExternalId("p1")
                .setPayloadHash("server-hash").setCollectedAt(time).setLastSeenAt(time)
                .setPayloadJson("{\"n\":9007199254740993}").build();
        when(stub.saveRawRecord(any())).thenReturn(raw);
        when(stub.getRawRecord(any())).thenReturn(raw);
        mvc.perform(post("/api/admin/collection/raw-records").with(csrf()).contentType("application/json").content(
                "{\"syncId\":7,\"externalId\":\"p1\",\"payloadHash\":\"ignored\",\"payload\":{\"n\":9007199254740993},\"collectedAt\":\"2026-10-23T09:00:00+09:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rawRecordId").value(9))
                .andExpect(jsonPath("$.payloadHash").value("server-hash")).andExpect(jsonPath("$.payload.n").value(9007199254740993L));
        mvc.perform(get("/api/admin/collection/raw-records/9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rawRecordId").value(9)).andExpect(jsonPath("$.source").value("YOUTH_POLICY"))
                .andExpect(jsonPath("$.externalId").value("p1")).andExpect(jsonPath("$.lastSeenAt").exists());
        verify(stub).saveRawRecord(argThat(request -> request.getSyncId() == 7 && request.getExternalId().equals("p1")
                && request.getPayloadJson().contains("9007199254740993") && request.getCollectedAt().equals(time)));
        verify(stub).getRawRecord(GetRawRecordRequest.newBuilder().setRawRecordId(9).build());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void translatesRemoteErrorsAndConnectionFailures() throws Exception {
        var trailers = new Metadata();
        trailers.put(Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER), "COLLECTION_004");
        when(stub.getSyncJob(any())).thenThrow(Status.NOT_FOUND.withDescription("private").asRuntimeException(trailers));
        mvc.perform(get("/api/admin/collection/syncs/7")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLLECTION_004"));
        for (var status : List.of(Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED, Status.UNAUTHENTICATED)) {
            doThrow(status.asRuntimeException()).when(stub).getRawRecord(any());
            mvc.perform(get("/api/admin/collection/raw-records/7")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("COLLECTION_008"));
        }
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void invalidInputsHaveCollectionErrorCodeAndNeverReachGrpc() throws Exception {
        for (String id : List.of("text", "0", "-1", "999999999999999999999")) {
            mvc.perform(post("/api/admin/collection/syncs/" + id + "/retry").with(csrf())).andExpect(status().isBadRequest());
            mvc.perform(get("/api/admin/collection/syncs/" + id)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COLLECTION_003"));
            mvc.perform(get("/api/admin/collection/raw-records/" + id)).andExpect(status().isBadRequest());
        }
        for (String source : List.of("UNKNOWN", "UNRECOGNIZED", "COLLECTION_SOURCE_UNSPECIFIED")) {
            mvc.perform(post("/api/admin/collection/syncs/" + source).with(csrf())).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COLLECTION_003"));
        }
        for (String date : List.of("", "2026-02-30", "0000-01-01", "+10000-01-01")) {
            mvc.perform(get("/api/admin/collection/syncs").param("date", date)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COLLECTION_003"));
        }
        mvc.perform(get("/api/admin/collection/syncs")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COLLECTION_003"));
        for (String size : List.of("abc", "0", "-1", "101")) {
            mvc.perform(post("/api/admin/collection/syncs/YOUTH_POLICY").with(csrf()).param("pageSize", size))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COLLECTION_003"));
        }
        mvc.perform(post("/api/admin/collection/raw-records").with(csrf()).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COLLECTION_002"));
        mvc.perform(post("/api/admin/collection/raw-records").with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COLLECTION_002"));
        verify(stub, never()).startSync(any()); verify(stub, never()).getSyncJob(any());
        verify(stub, never()).getRawRecord(any()); verify(stub, never()).listSyncJobsByDate(any());
        verify(stub, never()).saveRawRecord(any());
        verify(stub, never()).retrySync(any());
    }

    @Test
    @WithMockUser(authorities = "USER")
    void memberIsForbidden() throws Exception {
        mvc.perform(post("/api/admin/collection/syncs/1/retry").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/collection/syncs/1")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COLLECTION_007"));
        mvc.perform(post("/api/admin/collection/syncs/YOUTH_POLICY").with(csrf())).andExpect(status().isForbidden());
        verify(stub, never()).getSyncJob(any()); verify(stub, never()).startSync(any());
        verify(stub, never()).retrySync(any());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/api/admin/collection/syncs/1/retry").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/collection/syncs/1")).andExpect(status().isUnauthorized());
        verify(stub, never()).getSyncJob(any());
    }
}
