package com.team3.backend.tracking.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FailureDiagnosticTest {
    @Test
    void diagnosticIncludesCauseTypesAndFramesButNeverExceptionMessages() {
        var failure = new IllegalStateException("SECRET profile data", new IllegalArgumentException("SECRET remote body"));
        var trace = FailureReasons.diagnosticTrace(failure);
        assertThat(trace).contains("IllegalStateException", "IllegalArgumentException", "FailureDiagnosticTest");
        assertThat(trace).doesNotContain("SECRET", "profile data", "remote body");
    }

    @Test
    void nestedDiagnosticIsBounded() {
        RuntimeException failure = new IllegalStateException("inner");
        for (int i = 0; i < 30; i++) failure = new IllegalStateException("outer", failure);
        assertThat(FailureReasons.diagnosticTrace(failure).lines().filter(line -> line.equals("java.lang.IllegalStateException")).count())
                .isEqualTo(5);
    }
}
