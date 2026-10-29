package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.MatchTrackingConfig;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class MatchTrackingConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(MatchTrackingConfig.class);
    @Test void defaultsAreOffOneUnresolvedAndInterestRequired() {
        runner.run(context->{
            assertThat(context).hasNotFailed(); var props=context.getBean(MatchTrackingProperties.class);
            assertThat(props.enabled()).isFalse(); assertThat(props.possibly().maxUnresolved()).isEqualTo(1);
            assertThat(props.possibly().requireInterest()).isTrue(); assertThat(props.summaryLink()).isEmpty();
        });
    }
    @Test void exactConfirmedKeysBind() {
        runner.withPropertyValues("yon.tracking.match.enabled=true","yon.tracking.match.summary-link=/test-recommendations",
                "yon.tracking.match.possibly.max-unresolved=2","yon.tracking.match.possibly.require-interest=false")
                .run(context->{
                    assertThat(context).hasNotFailed(); var props=context.getBean(MatchTrackingProperties.class);
                    assertThat(props.possibly().maxUnresolved()).isEqualTo(2); assertThat(props.possibly().requireInterest()).isFalse();
                });
    }
    @Test void enabledWithoutVerifiedSummaryPathFailsClosed() {
        runner.withPropertyValues("yon.tracking.match.enabled=true").run(context->assertThat(context).hasFailed());
    }
    @Test void externalPathAndNegativeThresholdAreRejected() {
        assertThatThrownBy(()->new MatchTrackingProperties(true,new MatchTrackingProperties.Possibly(1,true),"https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MatchTrackingProperties.Possibly(-1,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void matchDisabledDoesNotRegisterTheBatchListener() {
        runner.withUserConfiguration(MatchBatchObservationListener.class)
                .withBean(MatchCandidatePage.class,()->org.mockito.Mockito.mock(MatchCandidatePage.class))
                .run(c->{assertThat(c).hasNotFailed(); assertThat(c).doesNotHaveBean(MatchBatchObservationListener.class);});
    }
    @Test void explicitMatchOptInRegistersObservationOnlyListener() {
        runner.withUserConfiguration(MatchBatchObservationListener.class)
                .withBean(MatchCandidatePage.class,()->org.mockito.Mockito.mock(MatchCandidatePage.class))
                .withPropertyValues("yon.tracking.match.enabled=true","yon.tracking.match.summary-link=/test-recommendations")
                .run(c->{assertThat(c).hasNotFailed(); assertThat(c).hasSingleBean(MatchBatchObservationListener.class);
                    assertThat(c).doesNotHaveBean(MatchOutboxTransaction.class);});
    }
}
