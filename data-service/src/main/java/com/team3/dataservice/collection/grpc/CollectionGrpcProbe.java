package com.team3.dataservice.collection.grpc;

import com.team3.contracts.collection.v1.CollectionServiceGrpc;
import com.team3.contracts.collection.v1.ListSyncJobsByDateRequest;
import com.team3.dataservice.global.security.InternalKeyServerInterceptor;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Standalone image probe: authenticated, read-only RPC through the real service and DB. */
public final class CollectionGrpcProbe {
    private CollectionGrpcProbe() {}

    public static void main(String[] args) {
        System.exit(run(System.getenv(), System.err));
    }

    static int run(Map<String, String> environment, PrintStream errors) {
        var key = environment.get("DATA_SERVICE_INTERNAL_API_KEY");
        if (key == null || key.isBlank()) {
            errors.println("gRPC collection probe requires DATA_SERVICE_INTERNAL_API_KEY");
            return 1;
        }
        try {
            var host = environment.getOrDefault("DATA_SERVICE_GRPC_PROBE_HOST", "127.0.0.1");
            var port = Integer.parseInt(environment.getOrDefault("DATA_SERVICE_GRPC_PORT", "9092"));
            var channel = Grpc.newChannelBuilderForAddress(host, port, InsecureChannelCredentials.create())
                    .maxInboundMessageSize(16 * 1024 * 1024).build();
            try {
                var headers = new Metadata();
                headers.put(InternalKeyServerInterceptor.INTERNAL_KEY, key);
                CollectionServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .withDeadlineAfter(2, TimeUnit.SECONDS)
                        .listSyncJobsByDate(ListSyncJobsByDateRequest.newBuilder()
                                .setDate(LocalDate.now(ZoneId.of("Asia/Seoul")).toString()).build());
                return 0;
            } finally {
                channel.shutdownNow();
            }
        } catch (StatusRuntimeException exception) {
            // Never print the remote description, metadata or key.
            errors.println("gRPC collection probe failed: " + exception.getStatus().getCode());
        } catch (RuntimeException exception) {
            errors.println("gRPC collection probe configuration invalid");
        }
        return 1;
    }
}
