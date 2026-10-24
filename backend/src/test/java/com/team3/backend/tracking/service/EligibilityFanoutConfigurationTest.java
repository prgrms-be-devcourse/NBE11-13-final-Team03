package com.team3.backend.tracking.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import static org.assertj.core.api.Assertions.*;

class EligibilityFanoutConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EligibilityFanoutCoordinator.class, EligibilityFanoutBatch.class, EligibilityFanoutWorker.class);

    @Test
    void noOptInDoesNotRegisterTheNewConsumer() {
        context.run(this::disabled);
    }

    @Test
    void fanoutFlagAloneDoesNotEnableWithoutOutboxPath() {
        context.withPropertyValues("yon.tracking.eligibility-fanout.enabled=true").run(this::disabled);
    }

    @Test
    void outboxFlagAloneDoesNotEnableFanout() {
        context.withPropertyValues("yon.tracking.eligibility-outbox.enabled=true").run(this::disabled);
    }

    private void disabled(AssertableApplicationContext result) {
        assertThat(result).hasNotFailed().doesNotHaveBean(EligibilityFanoutCoordinator.class)
                .doesNotHaveBean(EligibilityFanoutBatch.class).doesNotHaveBean(EligibilityFanoutWorker.class);
    }
}
