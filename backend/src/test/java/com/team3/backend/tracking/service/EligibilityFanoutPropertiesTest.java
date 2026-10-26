package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.EventFanoutConfig;
import com.team3.backend.tracking.config.EventFanoutProperties;
import com.team3.backend.tracking.config.EligibilityFanoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class EligibilityFanoutPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(EventFanoutConfig.class);

    @Test
    void eligibilityDefaultsToSmallPageAndStaysDisabledIndependentlyOfDateWorker() {
        context.withPropertyValues("yon.tracking.fanout.enabled=true", "yon.tracking.fanout.batch-size=400")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(EligibilityFanoutProperties.class).batchSize()).isEqualTo(25);
                    assertThat(result.getBean(EligibilityFanoutProperties.class).enabled()).isFalse();
                    assertThat(result.getBean(EventFanoutProperties.class).batchSize()).isEqualTo(400);
                });
    }

    @Test
    void pageAndRetrySettingsCanBeTunedWithoutChangingDateWorker() {
        context.withPropertyValues("yon.tracking.eligibility-fanout.batch-size=10", "yon.tracking.eligibility-fanout.max-attempts=3")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(EligibilityFanoutProperties.class).batchSize()).isEqualTo(10);
                    assertThat(result.getBean(EligibilityFanoutProperties.class).maxAttempts()).isEqualTo(3);
                    assertThat(result.getBean(EventFanoutProperties.class).batchSize()).isEqualTo(500);
                });
    }

    @Test
    void zeroPageIsRejectedAtBinding() {
        context.withPropertyValues("yon.tracking.eligibility-fanout.batch-size=0")
                .run(result -> assertThat(result).hasFailed());
    }
}
