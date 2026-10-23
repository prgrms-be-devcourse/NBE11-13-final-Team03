package com.team3.backend.client.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/** Scoped to the collection stub, never registered as a global interceptor. */
public class InternalKeyClientInterceptor implements ClientInterceptor {
    static final Metadata.Key<String> INTERNAL_KEY = Metadata.Key.of("x-internal-key", Metadata.ASCII_STRING_MARSHALLER);
    private final String key;

    public InternalKeyClientInterceptor(String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("yon.data-service.internal-api-key must not be blank");
        this.key = key;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
            CallOptions options, Channel channel) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(channel.newCall(method, options)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
                headers.discardAll(INTERNAL_KEY);
                headers.put(INTERNAL_KEY, key);
                super.start(listener, headers);
            }
        };
    }
}
