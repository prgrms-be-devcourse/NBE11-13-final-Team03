package com.team3.backend.client.grpc;

import com.team3.contracts.collection.v1.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Boot channel and HTTP controller, with a local fake Netty gRPC service. */
@ActiveProfiles("test")
@SpringBootTest(properties = "app.scheduling.enabled=false")
@AutoConfigureMockMvc
class CollectionGrpcClientIntegrationTest {
    private static final AtomicReference<String> key = new AtomicReference<>();
    private static final AtomicReference<Deadline> deadline = new AtomicReference<>();
    private static final Server server = startServer();
    @Autowired MockMvc mvc;
    @Autowired CollectionServiceGrpc.CollectionServiceBlockingStub stub;

    private static Server startServer() {
        try {
            return Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
                    .intercept(new ServerInterceptor() {
                        @Override
                        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                            key.set(headers.get(InternalKeyClientInterceptor.INTERNAL_KEY));
                            return next.startCall(call, headers);
                        }
                    })
                    .addService(new CollectionServiceGrpc.CollectionServiceImplBase() {
                        @Override
                        public void startSync(StartSyncRequest request, StreamObserver<SyncStartResponse> observer) {
                            deadline.set(Context.current().getDeadline());
                            observer.onNext(SyncStartResponse.newBuilder().setJobId(7).setSource(request.getSource())
                                    .setStatus(SyncJobStatus.PENDING).setMessage("accepted").build());
                            observer.onCompleted();
                        }
                        @Override
                        public void getSyncJob(GetSyncJobRequest request, StreamObserver<SyncJob> observer) {
                            deadline.set(Context.current().getDeadline());
                            // More than the gRPC 4 MiB default: verifies the configured 16 MiB channel limit.
                            observer.onNext(SyncJob.newBuilder().setJobId(request.getJobId()).setSource(CollectionSource.YOUTH_POLICY)
                                    .setStatus(SyncJobStatus.FAILED).setFailureReason("x".repeat(5 * 1024 * 1024)).build());
                            observer.onCompleted();
                        }
                    }).build().start();
        } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.grpc.client.channel.data-service.target", () -> "static://localhost:" + server.getPort());
    }

    @AfterAll
    static void stop() throws Exception { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void sendsDedicatedKeyAndPerCallDeadlinesOverRealTransport() throws Exception {
        mvc.perform(post("/api/admin/collection/syncs/YOUTH_POLICY").with(csrf()))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/admin/collection/syncs/7"));
        assertEquals("test-data-service-internal-api-key", key.get());
        assertNotNull(deadline.get());
        assertTrue(deadline.get().timeRemaining(TimeUnit.MILLISECONDS) <= 3000);
        mvc.perform(get("/api/admin/collection/syncs/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.failureReason").value("x".repeat(5 * 1024 * 1024)));
        assertTrue(deadline.get().timeRemaining(TimeUnit.MILLISECONDS) <= 10000);
        assertTrue(deadline.get().timeRemaining(TimeUnit.MILLISECONDS) > 3000);
        assertEquals("test-data-service-internal-api-key", key.get());
    }

    @Test
    void blankKeyCannotStartClient() {
        assertThrows(IllegalStateException.class, () -> new InternalKeyClientInterceptor(" "));
        assertThrows(IllegalStateException.class, () -> new InternalKeyClientInterceptor(null));
    }
}
