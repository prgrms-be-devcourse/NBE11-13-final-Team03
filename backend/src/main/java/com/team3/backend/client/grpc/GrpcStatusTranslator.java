package com.team3.backend.client.grpc;

import com.team3.backend.collection.exception.CollectionErrorCode;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GrpcStatusTranslator {
    private static final Logger log = LoggerFactory.getLogger(GrpcStatusTranslator.class);
    static final Metadata.Key<String> ERROR_CODE = Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER);

    public BusinessException translate(StatusRuntimeException exception) {
        var status = exception.getStatus().getCode();
        if (status == Status.Code.UNAUTHENTICATED || status == Status.Code.PERMISSION_DENIED
                || status == Status.Code.UNIMPLEMENTED) {
            log.warn("DataService gRPC configuration rejected: status={}; check internal key and target", status);
            return new BusinessException(CollectionErrorCode.DATA_SERVICE_UNAVAILABLE);
        }
        if (status == Status.Code.DEADLINE_EXCEEDED) {
            return new BusinessException(CollectionErrorCode.DATA_SERVICE_UNAVAILABLE);
        }
        var code = exception.getTrailers() == null ? null : exception.getTrailers().get(ERROR_CODE);
        for (var error : CollectionErrorCode.values()) {
            if (error != CollectionErrorCode.ACCESS_DENIED && error != CollectionErrorCode.DATA_SERVICE_UNAVAILABLE && error.getCode().equals(code)) {
                return new BusinessException(error);
            }
        }
        return new BusinessException(switch (status) {
            case INVALID_ARGUMENT, OUT_OF_RANGE -> CollectionErrorCode.INVALID_ARGUMENT;
            case NOT_FOUND -> CollectionErrorCode.NOT_FOUND;
            case FAILED_PRECONDITION, ALREADY_EXISTS -> CollectionErrorCode.INVALID_STATE;
            case UNAVAILABLE, RESOURCE_EXHAUSTED -> CollectionErrorCode.DATA_SERVICE_UNAVAILABLE;
            default -> GlobalErrorCode.INTERNAL_SERVER_ERROR;
        });
    }
}
