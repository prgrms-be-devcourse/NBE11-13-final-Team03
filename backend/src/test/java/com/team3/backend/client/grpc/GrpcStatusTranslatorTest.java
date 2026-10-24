package com.team3.backend.client.grpc;

import com.team3.backend.collection.exception.CollectionErrorCode;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class GrpcStatusTranslatorTest {
    private final GrpcStatusTranslator translator = new GrpcStatusTranslator();

    @Test
    void preservesCollectionCodesAndSafeMessages() {
        for (var error : CollectionErrorCode.values()) {
            if (error == CollectionErrorCode.ACCESS_DENIED || error == CollectionErrorCode.DATA_SERVICE_UNAVAILABLE) continue;
            var metadata = new Metadata(); metadata.put(GrpcStatusTranslator.ERROR_CODE, error.getCode());
            var result = translator.translate(Status.INTERNAL.withDescription("sensitive server exception").asRuntimeException(metadata));
            assertSame(error, result.getErrorCode());
            assertEquals(error.getMessage(), result.getMessage());
            assertNull(result.getCause());
        }
    }

    static Stream<Arguments> statuses() {
        return Stream.of(
                Arguments.of(Status.INVALID_ARGUMENT, "COLLECTION_003"),
                Arguments.of(Status.NOT_FOUND, "COLLECTION_004"),
                Arguments.of(Status.FAILED_PRECONDITION, "COLLECTION_005"),
                Arguments.of(Status.UNAVAILABLE, "COLLECTION_008"),
                Arguments.of(Status.DEADLINE_EXCEEDED, "COLLECTION_008"),
                Arguments.of(Status.UNAUTHENTICATED, "COLLECTION_008"),
                Arguments.of(Status.PERMISSION_DENIED, "COLLECTION_008"),
                Arguments.of(Status.UNIMPLEMENTED, "COLLECTION_008"),
                Arguments.of(Status.INTERNAL, "COMMON_003"),
                Arguments.of(Status.UNKNOWN, "COMMON_003"));
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void translatesStatusWithoutTrailer(Status status, String code) {
        assertEquals(code, translator.translate(status.asRuntimeException()).getErrorCode().getCode());
    }

    @Test
    void ignoresUnknownAndPublicOnlyErrorCodes() {
        for (String code : new String[]{"AUTH_008", "UNKNOWN", "COLLECTION_007", "COLLECTION_008"}) {
            var trailers = new Metadata(); trailers.put(GrpcStatusTranslator.ERROR_CODE, code);
            assertEquals("COMMON_003", translator.translate(Status.INTERNAL.asRuntimeException(trailers)).getErrorCode().getCode());
        }
    }

    @Test
    void authFailuresAlwaysBecomeServiceUnavailableAndNeverLogServerDetails(CapturedOutput output) {
        var trailers = new Metadata(); trailers.put(GrpcStatusTranslator.ERROR_CODE, "COLLECTION_004");
        var result = translator.translate(Status.UNAUTHENTICATED.withDescription("secret-key-value").asRuntimeException(trailers));
        assertEquals("COLLECTION_008", result.getErrorCode().getCode());
        assertTrue(output.toString().contains("WARN"));
        assertTrue(output.toString().contains("UNAUTHENTICATED"));
        assertFalse(output.toString().contains("secret-key-value"));
    }
}
