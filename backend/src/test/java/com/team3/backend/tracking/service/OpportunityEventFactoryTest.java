package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEventSuppressionReason;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import com.team3.backend.tracking.domain.model.ApplicationStatusTransition;
import com.team3.backend.tracking.domain.model.DeadlineSoonCandidate;
import com.team3.backend.tracking.domain.model.DeadlineThreshold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpportunityEventFactoryTest {

    private static final LocalDate TODAY =
            LocalDate.of(2026, 10, 20);

    private static final Instant NOW =
            Instant.parse("2026-10-20T02:00:00Z");

    private OpportunityEventFactory factory;
    private Opportunity opportunity;
    private OpportunityVersion version;

    @BeforeEach
    void setUp() {
        factory = new OpportunityEventFactory();

        opportunity = Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY)
                .title("청년 지원 정책")
                .build();

        ReflectionTestUtils.setField(
                opportunity,
                "id",
                10L
        );

        version = OpportunityVersion.builder()
                .opportunity(opportunity)
                .versionNo(1)
                .normalizedSnapshot(Map.of())
                .normalizationVersion("v1")
                .build();
    }

    @Test
    void 모집_시작_상태_변화를_정책_이벤트로_변환한다() {
        ApplicationStatusTransition transition =
                new ApplicationStatusTransition(
                        TODAY,
                        ApplicationStatus.UPCOMING,
                        ApplicationStatus.OPEN,
                        OpportunityEventType.APPLICATION_OPENED
                );

        OpportunityEvent event =
                factory.createStatusTransitionEvent(
                        opportunity,
                        version,
                        transition,
                        NOW
                );

        assertThat(event.getEventType())
                .isEqualTo(
                        OpportunityEventType.APPLICATION_OPENED
                );
        assertThat(event.getEventDate()).isEqualTo(TODAY);
        assertThat(event.getDedupKey())
                .isEqualTo(
                        "APPLICATION_OPENED:10:2026-10-20"
                );
        assertThat(event.isNotificationEligible()).isTrue();
        assertThat(event.getSuppressionReason()).isNull();
        assertThat(event.getPayload())
                .containsEntry("previousStatus", "UPCOMING")
                .containsEntry("currentStatus", "OPEN")
                .containsEntry("startDate", "2026-10-20");
    }

    @Test
    void 모집_마감_상태_변화는_실제_마감일로_이벤트를_생성한다() {
        ApplicationStatusTransition transition =
                new ApplicationStatusTransition(
                        TODAY,
                        ApplicationStatus.OPEN,
                        ApplicationStatus.CLOSED,
                        OpportunityEventType.APPLICATION_CLOSED
                );

        OpportunityEvent event =
                factory.createStatusTransitionEvent(
                        opportunity,
                        version,
                        transition,
                        NOW
                );

        assertThat(event.getEventDate())
                .isEqualTo(TODAY.minusDays(1));
        assertThat(event.getDedupKey())
                .isEqualTo(
                        "APPLICATION_CLOSED:10:2026-10-19"
                );
        assertThat(event.getPayload())
                .containsEntry("endDate", "2026-10-19")
                .containsEntry("detectedDate", "2026-10-20");
    }

    @Test
    void 오늘의_마감_임박_후보는_알림_가능_이벤트가_된다() {
        DeadlineSoonCandidate candidate =
                new DeadlineSoonCandidate(
                        TODAY,
                        TODAY.plusDays(3),
                        DeadlineThreshold.D_3
                );

        OpportunityEvent event =
                factory.createDeadlineSoonEvent(
                        opportunity,
                        version,
                        candidate,
                        TODAY,
                        NOW
                );

        assertThat(event.getEventType())
                .isEqualTo(OpportunityEventType.DEADLINE_SOON);
        assertThat(event.getDedupKey())
                .isEqualTo(
                        "DEADLINE_SOON:10:D-3:2026-10-23"
                );
        assertThat(event.getPayload())
                .containsEntry("threshold", "D-3")
                .containsEntry("applicationEndDate", "2026-10-23");
        assertThat(event.isNotificationEligible()).isTrue();
        assertThat(event.getSuppressionReason()).isNull();
    }

    @Test
    void 지난_기준일을_복구하면_이력은_남기되_알림을_억제한다() {
        DeadlineSoonCandidate candidate =
                new DeadlineSoonCandidate(
                        TODAY.minusDays(2),
                        TODAY.plusDays(5),
                        DeadlineThreshold.D_7
                );

        OpportunityEvent event =
                factory.createDeadlineSoonEvent(
                        opportunity,
                        version,
                        candidate,
                        TODAY,
                        NOW
                );

        assertThat(event.isNotificationEligible()).isFalse();
        assertThat(event.getSuppressionReason())
                .isEqualTo(
                        OpportunityEventSuppressionReason
                                .THRESHOLD_ALREADY_PASSED
                );
        assertThat(event.getDedupKey())
                .isEqualTo(
                        "DEADLINE_SOON:10:D-7:2026-10-25"
                );
    }

    @Test
    void 미래_기준일의_마감_임박_이벤트는_생성할_수_없다() {
        DeadlineSoonCandidate candidate =
                new DeadlineSoonCandidate(
                        TODAY.plusDays(1),
                        TODAY.plusDays(8),
                        DeadlineThreshold.D_7
                );

        assertThatThrownBy(() ->
                factory.createDeadlineSoonEvent(
                        opportunity,
                        version,
                        candidate,
                        TODAY,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "미래 기준일의 마감 임박 이벤트는 생성할 수 없습니다."
                );
    }

    @Test
    void 저장되지_않은_정책으로는_이벤트를_생성할_수_없다() {
        Opportunity unsavedOpportunity =
                Opportunity.builder()
                        .opportunityType(
                                OpportunityType.YOUTH_POLICY
                        )
                        .title("저장 전 정책")
                        .build();

        OpportunityVersion unsavedVersion =
                OpportunityVersion.builder()
                        .opportunity(unsavedOpportunity)
                        .versionNo(1)
                        .normalizedSnapshot(Map.of())
                        .normalizationVersion("v1")
                        .build();

        DeadlineSoonCandidate candidate =
                new DeadlineSoonCandidate(
                        TODAY,
                        TODAY.plusDays(1),
                        DeadlineThreshold.D_1
                );

        assertThatThrownBy(() ->
                factory.createDeadlineSoonEvent(
                        unsavedOpportunity,
                        unsavedVersion,
                        candidate,
                        TODAY,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "저장되지 않은 정책으로는 이벤트를 생성할 수 없습니다."
                );
    }
}
