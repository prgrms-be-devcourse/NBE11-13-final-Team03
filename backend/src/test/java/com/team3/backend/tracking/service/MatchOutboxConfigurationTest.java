package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class MatchOutboxConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(MatchTrackingConfig.class,MatchOutboxConfig.class);
    @Test void defaultOffDoesNotAuthorizeOutbox() {
        runner.run(c->{assertThat(c).hasNotFailed(); assertThat(c).doesNotHaveBean(MatchOutboxConfig.Ready.class);
            assertThat(c.getBean(MatchOutboxProperties.class).enabled()).isFalse();});
    }
    @Test void observationMustBeEnabledFirst() {
        runner.withPropertyValues("yon.tracking.match.outbox.enabled=true").run(c->assertThat(c).hasFailed());
    }
    @Test void bothRoutesCanCoexistWithPositiveOwnershipAssignedByTheSameFlag() {
        runner.withPropertyValues("yon.tracking.match.enabled=true","yon.tracking.match.summary-link=/test-recommendations",
                "yon.tracking.match.outbox.enabled=true","yon.tracking.eligibility-outbox.enabled=true")
                .run(c->{assertThat(c).hasNotFailed(); assertThat(c).hasSingleBean(MatchOutboxConfig.Ready.class);});
    }
    @Test void explicitLocalRouteCanBeAuthorized() {
        runner.withPropertyValues("yon.tracking.match.enabled=true","yon.tracking.match.summary-link=/test-recommendations",
                "yon.tracking.match.outbox.enabled=true").run(c->{assertThat(c).hasNotFailed();
                    assertThat(c).hasSingleBean(MatchOutboxConfig.Ready.class);});
    }
    @Test void summaryBudgetMustBeBounded() {
        assertThatThrownBy(()->new MatchOutboxProperties(true,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MatchOutboxProperties(true,501)).isInstanceOf(IllegalArgumentException.class);
    }
}
