package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.outbox.domain.entity.OutboxStatus;
import com.team3.backend.tracking.domain.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;
import static org.assertj.core.api.Assertions.*;

class DateNotificationMessageFactoryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private final ApplicationStatusCalculator calculator = new ApplicationStatusCalculator();
    private final DateNotificationMessageFactory factory =
            new DateNotificationMessageFactory(calculator, new DeadlineSoonDetector(calculator));
    private final Opportunity policy = Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY)
            .title("Policy").build();
    private final OpportunityVersion version = OpportunityVersion.builder().opportunity(policy).versionNo(1)
            .normalizationVersion("test").normalizedSnapshot(Map.of()).build();

    @Test
    void deadlineMessageIsCompleteAndUsesExclusiveKstMidnightExpiry() {
        var event = deadline();
        var prepared = factory.prepare(event, policy, List.of(period(TODAY.minusDays(7), TODAY.plusDays(3))), NOW)
                .orElseThrow();
        var outbox = factory.create(event, 7L, prepared, NOW);
        assertThat(prepared.expiresAt()).isEqualTo(Instant.parse("2026-10-26T15:00:00Z"));
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getNextRetryAt()).isEqualTo(NOW);
        assertThat(outbox.getDedupKey()).isEqualTo("NOTIFICATION_REQUESTED:" + event.getEventId() + ":7");
        assertThat(outbox.getPayload()).containsEntry("schemaVersion", 1)
                .containsEntry("messageId", outbox.getMessageId().toString())
                .containsEntry("eventType", "NOTIFICATION_REQUESTED")
                .containsEntry("sourceEventId", event.getEventId().toString())
                .containsEntry("correlationId", event.getCorrelationId().toString())
                .containsEntry("causationId", event.getEventId().toString())
                .containsEntry("userId", 7L).containsEntry("opportunityId", 10L)
                .containsEntry("opportunityVersionId", 20L).containsEntry("type", "DEADLINE_SOON")
                .containsEntry("channels", List.of("IN_APP", "PUSH"))
                .containsEntry("link", "/opportunities/10")
                .containsEntry("expiresAt", "2026-10-26T15:00:00Z")
                .containsEntry("payload", event.getPayload())
                .containsKeys("title", "content", "occurredAt");
    }

    @Test
    void changedDeadlineOrPassedThresholdDoesNotCreateNewRequest() {
        var event = deadline();
        assertThat(factory.prepare(event, policy, List.of(period(TODAY.minusDays(7), TODAY.plusDays(4))), NOW))
                .isEmpty();
        assertThat(factory.prepare(event, policy, List.of(period(TODAY.minusDays(7), TODAY.plusDays(3))),
                NOW.plus(Duration.ofDays(1)))).isEmpty();
    }

    @Test
    void alwaysOpenAndInvalidWindowsDoNotCreateDeadlineRequests() {
        var fixed = period(TODAY.minusDays(7), TODAY.plusDays(3));
        var always = new ApplicationPeriod(PeriodType.ALWAYS, null, null, ValidationStatus.VALID, "");
        assertThat(factory.prepare(deadline(), policy, List.of(fixed, always), NOW)).isEmpty();
        var invalid = new ApplicationPeriod(PeriodType.FIXED, fixed.startDate(), fixed.endDate(),
                ValidationStatus.INVALID, "");
        assertThat(factory.prepare(deadline(), policy, List.of(invalid), NOW)).isEmpty();
    }

    @Test
    void openedExpiryIsEarlierOfThreeDaysAndClosingBoundary() {
        var event = event(OpportunityEventType.APPLICATION_OPENED, Map.of("startDate", TODAY.toString()), true);
        var prepared = factory.prepare(event, policy, List.of(period(TODAY, TODAY)), NOW).orElseThrow();
        assertThat(prepared.expiresAt()).isEqualTo(Instant.parse("2026-10-23T15:00:00Z"));
        assertThat(prepared.channels()).containsExactly("IN_APP", "PUSH");
        var withoutEnd = factory.prepare(event, policy, List.of(period(TODAY, null)), NOW).orElseThrow();
        assertThat(withoutEnd.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        var multiple = factory.prepare(event, policy,
                List.of(period(TODAY, TODAY), period(TODAY, TODAY.plusDays(7))), NOW).orElseThrow();
        assertThat(multiple.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        var partlyUnbounded = factory.prepare(event, policy,
                List.of(period(TODAY, TODAY), period(TODAY, null)), NOW).orElseThrow();
        assertThat(partlyUnbounded.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        assertThatThrownBy(() -> factory.create(event, 7L, prepared, prepared.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openedRecoveredAfterAWeekendExpiresFromCreationNotFromTheScannedDate() {
        // Friday (TODAY) was missed and recovered on Monday within recovery-days=3.
        var monday = Instant.parse("2026-10-25T16:00:00Z");
        ReflectionTestUtils.setField(policy, "id", 10L);
        ReflectionTestUtils.setField(version, "id", 20L);
        var recovered = OpportunityEvent.create(policy, version, OpportunityEventType.APPLICATION_OPENED, TODAY,
                "APPLICATION_OPENED:10:" + TODAY, Map.of("startDate", TODAY.toString()), true, null, monday);
        recovered.recordOrigin(UUID.randomUUID(), TODAY.atStartOfDay(KST).toInstant());

        var prepared = factory.prepare(recovered, policy, List.of(period(TODAY, null)), monday).orElseThrow();
        assertThat(prepared.expiresAt()).isEqualTo(monday.plus(Duration.ofDays(3)));
    }

    @Test
    void closedRequestsInAppOnlyAndOtherOpenPeriodInvalidatesIt() {
        var event = event(OpportunityEventType.APPLICATION_CLOSED,
                Map.of("endDate", TODAY.minusDays(1).toString()), true);
        var old = period(TODAY.minusDays(7), TODAY.minusDays(1));
        var prepared = factory.prepare(event, policy, List.of(old), NOW).orElseThrow();
        assertThat(prepared.channels()).containsExactly("IN_APP");
        assertThat(prepared.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        assertThat(factory.prepare(event, policy, List.of(old, period(TODAY.minusDays(1), TODAY.plusDays(9))), NOW))
                .isEmpty();
        assertThat(factory.prepare(event, policy, List.of(old), prepared.expiresAt())).isEmpty();
    }

    @Test
    void eventSuppressionIsHonoredAndMalformedPayloadIsNotSilentlyAccepted() {
        var suppressed = event(OpportunityEventType.DEADLINE_SOON, Map.of(), false);
        assertThat(factory.prepare(suppressed, policy, List.of(), NOW)).isEmpty();
        var broken = event(OpportunityEventType.DEADLINE_SOON,
                Map.of("applicationEndDate", TODAY.plusDays(3).toString(), "threshold", "D-UNKNOWN"), true);
        assertThatThrownBy(() -> factory.prepare(broken, policy, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OpportunityEvent deadline() {
        return event(OpportunityEventType.DEADLINE_SOON,
                Map.of("threshold", "D-3", "applicationEndDate", TODAY.plusDays(3).toString()), true);
    }

    private OpportunityEvent event(OpportunityEventType type, Map<String, Object> payload, boolean eligible) {
        ReflectionTestUtils.setField(policy, "id", 10L);
        ReflectionTestUtils.setField(version, "id", 20L);
        return OpportunityEvent.create(policy, version, type, TODAY, type + ":10:" + TODAY, payload, eligible,
                eligible ? null : OpportunityEventSuppressionReason.THRESHOLD_ALREADY_PASSED, NOW);
    }

    private ApplicationPeriod period(LocalDate start, LocalDate end) {
        return new ApplicationPeriod(PeriodType.FIXED, start, end, ValidationStatus.VALID, "");
    }
}
