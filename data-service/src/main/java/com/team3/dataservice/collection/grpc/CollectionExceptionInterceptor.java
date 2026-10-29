package com.team3.dataservice.collection.grpc;

import com.team3.dataservice.collection.exception.CollectionErrorCode;
import com.team3.dataservice.global.exception.GlobalErrorCode;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

@Component
@GlobalServerInterceptor
public class CollectionExceptionInterceptor implements ServerInterceptor {
    public static final Metadata.Key<String> ERROR_CODE = Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (RuntimeException exception) {
                    close(call, exception);
                }
            }
        };
    }

    private void close(ServerCall<?, ?> call, RuntimeException exception) {
        CollectionErrorCode error;
        Status status;
        if (exception instanceof JacksonException) {
            error = CollectionErrorCode.INVALID_BODY;
            status = Status.INVALID_ARGUMENT;
        } else if (exception instanceof IllegalArgumentException) {
            error = CollectionErrorCode.INVALID_ARGUMENT;
            status = Status.INVALID_ARGUMENT;
        } else if (exception instanceof EntityNotFoundException) {
            error = CollectionErrorCode.NOT_FOUND;
            status = Status.NOT_FOUND;
        } else if (exception instanceof TaskRejectedException) {
            error = CollectionErrorCode.EXECUTOR_UNAVAILABLE;
            status = Status.UNAVAILABLE;
        } else if (exception instanceof IllegalStateException) {
            error = CollectionErrorCode.INVALID_STATE;
            status = Status.FAILED_PRECONDITION;
        } else if (exception instanceof DataAccessException) {
            error = CollectionErrorCode.STORAGE_FAILURE;
            status = Status.INTERNAL;
        } else {
            call.close(Status.INTERNAL.withDescription(GlobalErrorCode.INTERNAL_SERVER_ERROR.getMessage()), new Metadata());
            return;
        }
        var trailers = new Metadata();
        trailers.put(ERROR_CODE, error.getCode());
        call.close(status.withDescription(error.getMessage()), trailers);
    }
}
