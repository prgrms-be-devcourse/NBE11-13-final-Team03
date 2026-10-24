package com.team3.backend.tracking.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class OpportunityVersionAnalysisConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OpportunityVersionAnalysisTransaction.class);

    @Test
    void analysisProducerIsDisabledByDefault() {
        context.run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(OpportunityVersionAnalysisTransaction.class));
    }

    @Test
    void explicitFalseDoesNotEnableAnalysisProducer() {
        context.withPropertyValues("yon.tracking.version-analysis.enabled=false")
                .run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(OpportunityVersionAnalysisTransaction.class));
    }
}
