package com.team3.backend.tracking.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class EligibilityOutboxConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EligibilityOutboxTransaction.class);

    @Test
    void provisionalWritePathIsDisabledWithoutExplicitOptIn() {
        context.run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(EligibilityOutboxTransaction.class));
    }

    @Test
    void explicitFalseAlsoKeepsTheWritePathDisabled() {
        context.withPropertyValues("app.tracking.eligibility-outbox-enabled=false")
                .run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(EligibilityOutboxTransaction.class));
    }
}
