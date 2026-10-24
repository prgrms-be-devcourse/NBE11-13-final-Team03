package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.eligibility.service.EligibilityEvaluator.Judgement;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static com.team3.backend.eligibility.domain.entity.EligibilityStatus.*;
import static org.assertj.core.api.Assertions.*;

class EligibilityNotificationMessageFactoryTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private final EligibilityNotificationMessageFactory factory = new EligibilityNotificationMessageFactory();
    private final ProvisionalEligibilityTransitionClassifier classifier = new ProvisionalEligibilityTransitionClassifier();

    @Test
    void producerMatchesSharedContractIncludingDecisionEvidence() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var expected = mapper.readTree(new ClassPathResource("contracts/notification-eligibility-v1.json")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var event = event();
        ReflectionTestUtils.setField(event, "eventId", UUID.fromString(expected.get("sourceEventId").asText()));
        ReflectionTestUtils.setField(event, "correlationId", UUID.fromString(expected.get("correlationId").asText()));
        var outbox = factory.create(event, assessment(NOT_ELIGIBLE, ELIGIBLE), NOW).orElseThrow();
        var actual = new LinkedHashMap<>(outbox.getPayload()); actual.put("messageId", expected.get("messageId").asText());
        assertThat(mapper.readTree(mapper.writeValueAsString(actual))).isEqualTo(expected);
        assertThat(outbox.getDedupKey()).isEqualTo("NOTIFICATION_REQUESTED:" + event.getEventId() + ":7");
    }

    @Test
    void candidateKindsKeepTheirDistinctRequestedChannels() {
        var became = factory.create(event(), assessment(NOT_ELIGIBLE, ELIGIBLE), NOW).orElseThrow();
        var downgraded = factory.create(event(), assessment(ELIGIBLE, NOT_ELIGIBLE), NOW).orElseThrow();
        var review = factory.create(event(), assessment(ELIGIBLE, POSSIBLY_ELIGIBLE), NOW).orElseThrow();
        assertThat(became.getPayload()).containsEntry("type", "BECAME_ELIGIBLE").containsEntry("channels", List.of("IN_APP", "PUSH"));
        assertThat(downgraded.getPayload()).containsEntry("type", "ELIGIBILITY_DOWNGRADED").containsEntry("channels", List.of("IN_APP"));
        assertThat(review.getPayload()).containsEntry("type", "ELIGIBILITY_REVIEW_REQUIRED").containsEntry("channels", List.of("IN_APP"));
    }

    @Test
    void expiryIsSevenDaysFromOccurrenceAndReplayCannotExtendIt() {
        var event = event(); var result = assessment(NOT_ELIGIBLE, ELIGIBLE);
        assertThat(factory.create(event, result, NOW.plus(Duration.ofDays(6))).orElseThrow().getPayload())
                .containsEntry("expiresAt", NOW.plus(Duration.ofDays(7)).toString());
        assertThat(factory.create(event, result, NOW.plus(Duration.ofDays(7)))).isEmpty();
    }

    @Test
    void rejectedAssessmentCannotBecomeARequest() {
        var good = assessment(NOT_ELIGIBLE, ELIGIBLE);
        var rejected = new EligibilityNotificationRevalidation(EligibilityNotificationRevalidation.Outcome.LATEST_STATUS_CHANGED,
                good.mappingPolicyVersion(), good.decision(), good.sourceComparison(), good.freshComparison(),
                good.revalidatedVersionId(), good.latestJudgement());
        assertThatThrownBy(() -> factory.create(event(), rejected, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mismatchedSourceVersionIsRejected() {
        var event = event(); ReflectionTestUtils.setField(event.getOpportunityVersion(), "id", 99L);
        assertThatThrownBy(() -> factory.create(event, assessment(NOT_ELIGIBLE, ELIGIBLE), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OpportunityEvent event() {
        var policy = Opportunity.builder().id(10L).build();
        var version = OpportunityVersion.builder().id(20L).opportunity(policy).build();
        return OpportunityEvent.create(policy, version, OpportunityEventType.ELIGIBILITY_CHANGED, LocalDate.of(2026, 10, 25),
                "ELIGIBILITY_CHANGED:10:19:20", Map.of("fromVersionId", 19L, "toVersionId", 20L), true, null, NOW);
    }

    private EligibilityNotificationRevalidation assessment(EligibilityStatus before, EligibilityStatus after) {
        var source = new EligibilityVersionComparison(7L, 19L, 20L, 1L, EligibilityResultService.RULE_VERSION,
                LocalDate.of(2026, 10, 24), judgement(before), judgement(after));
        var fresh = new EligibilityVersionComparison(7L, 19L, 20L, 9L, EligibilityResultService.RULE_VERSION,
                LocalDate.of(2026, 10, 25), judgement(before), judgement(after));
        return new EligibilityNotificationRevalidation(EligibilityNotificationRevalidation.Outcome.CANDIDATE,
                ProvisionalEligibilityTransitionClassifier.POLICY_VERSION, classifier.classify(before, after),
                source, fresh, 30L, judgement(after));
    }

    private Judgement judgement(EligibilityStatus status) {
        return new Judgement(new EligibilityEvaluation(status, List.of(), "test evidence"), ApplicationStatus.OPEN);
    }
}
