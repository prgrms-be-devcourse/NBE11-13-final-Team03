package com.team3.dataservice.collection.grpc;

import com.team3.contracts.collection.v1.CollectionServiceGrpc;
import com.team3.contracts.collection.v1.ListSyncJobsByDateRequest;
import com.team3.contracts.collection.v1.ListSyncJobsResponse;
import com.team3.dataservice.global.security.InternalKeyServerInterceptor;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollectionGrpcProbeTest {
    @Test
    void probeRequiresAuthenticationAndSuccessfulReadWithoutLeakingSecrets() throws Exception {
        var date = new AtomicReference<LocalDate>();
        var fail = new AtomicBoolean();
        var service = new CollectionServiceGrpc.CollectionServiceImplBase() {
            @Override
            public void listSyncJobsByDate(ListSyncJobsByDateRequest request, StreamObserver<ListSyncJobsResponse> observer) {
                date.set(LocalDate.parse(request.getDate()));
                if (fail.get()) observer.onError(Status.INTERNAL.withDescription("secret-server-detail").asRuntimeException());
                else { observer.onNext(ListSyncJobsResponse.getDefaultInstance()); observer.onCompleted(); }
            }
        };
        var server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
                .addService(ServerInterceptors.intercept(service, new InternalKeyServerInterceptor("probe-secret"))).build().start();
        try (var output = new ByteArrayOutputStream(); var errors = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            var env = new java.util.HashMap<String, String>();
            env.put("DATA_SERVICE_GRPC_PORT", Integer.toString(server.getPort()));
            assertEquals(1, CollectionGrpcProbe.run(env, errors));
            env.put("DATA_SERVICE_INTERNAL_API_KEY", "wrong-secret");
            assertEquals(1, CollectionGrpcProbe.run(env, errors));
            env.put("DATA_SERVICE_INTERNAL_API_KEY", "probe-secret");
            assertEquals(0, CollectionGrpcProbe.run(env, errors));
            assertNotNull(date.get());
            fail.set(true);
            assertEquals(1, CollectionGrpcProbe.run(env, errors));
            env.put("DATA_SERVICE_GRPC_PORT", "invalid");
            assertEquals(1, CollectionGrpcProbe.run(env, errors));
            var text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("UNAUTHENTICATED")); assertTrue(text.contains("INTERNAL"));
            assertFalse(text.contains("probe-secret")); assertFalse(text.contains("wrong-secret"));
            assertFalse(text.contains("secret-server-detail"));
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
