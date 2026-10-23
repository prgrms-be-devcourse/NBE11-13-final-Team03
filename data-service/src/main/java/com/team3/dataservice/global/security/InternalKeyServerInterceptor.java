package com.team3.dataservice.global.security;

import com.team3.dataservice.collection.grpc.CollectionExceptionInterceptor;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalKeyServerInterceptor implements ServerInterceptor {
    public static final Metadata.Key<String> INTERNAL_KEY = Metadata.Key.of("x-internal-key", Metadata.ASCII_STRING_MARSHALLER);
    private final byte[] expectedKey;

    public InternalKeyServerInterceptor(@Value("${internal.api-key}") String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("internal.api-key must not be blank");
        expectedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        var key = headers.get(INTERNAL_KEY);
        if (key == null || !MessageDigest.isEqual(expectedKey, key.getBytes(StandardCharsets.UTF_8))) {
            var trailers = new Metadata();
            trailers.put(CollectionExceptionInterceptor.ERROR_CODE, "AUTH_008");
            call.close(Status.UNAUTHENTICATED.withDescription("유효하지 않은 내부 API Key입니다."), trailers);
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
