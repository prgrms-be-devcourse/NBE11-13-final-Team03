package com.team3.backend.tracking.domain.entity;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpportunityEventTest {

    @Test
    void 신규_이벤트는_즉시_처리할_수_있는_PENDING_상태로_생성된다() {
        Opportunity opportunity = opportunity();
        OpportunityVersion version = version(opportunity);
        Instant now = Instant.parse("2026-10-20T02:00:00Z");

        OpportunityEvent event = OpportunityEvent.create(
                opportunity,
                version,
                OpportunityEventType.DEADLINE_SOON,
                LocalDate.of(2026, 10, 20),
                "DEADLINE_SOON:1:D-3:2026-10-23",
                Map.of(
                        "threshold", "D-3",
                        "endDate", "2026-10-23"
                ),
                true,
                null,
                now
        );

        assertThat(event.getProcessingStatus())
                .isEqualTo(
                        OpportunityEventProcessingStatus.PENDING
                );

        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextRetryAt()).isEqualTo(now);
        assertThat(event.getCreatedAt()).isEqualTo(now);
        assertThat(event.getOccurredAt()).isEqualTo(now);
        assertThat(event.isNotificationEligible()).isTrue();
        assertThat(event.getSuppressionReason()).isNull();
    }

    @Test
    void 지난_임계값_이벤트는_억제_사유와_함께_저장할_수_있다() {
        Opportunity opportunity = opportunity();
        OpportunityVersion version = version(opportunity);
        Instant now = Instant.parse("2026-10-20T02:00:00Z");

        OpportunityEvent event = OpportunityEvent.create(
                opportunity,
                version,
                OpportunityEventType.DEADLINE_SOON,
                LocalDate.of(2026, 10, 18),
                "DEADLINE_SOON:1:D-7:2026-10-25",
                Map.of(
                        "threshold", "D-7",
                        "endDate", "2026-10-25"
                ),
                false,
                OpportunityEventSuppressionReason
                        .THRESHOLD_ALREADY_PASSED,
                now
        );

        assertThat(event.isNotificationEligible()).isFalse();
        assertThat(event.getSuppressionReason())
                .isEqualTo(
                        OpportunityEventSuppressionReason
                                .THRESHOLD_ALREADY_PASSED
                );
    }

    @Test
    void 알림을_억제하면_억제_사유가_필수다() {
        Opportunity opportunity = opportunity();
        OpportunityVersion version = version(opportunity);
        Instant now = Instant.parse("2026-10-20T02:00:00Z");

        assertThatThrownBy(() -> OpportunityEvent.create(
                opportunity,
                version,
                OpportunityEventType.DEADLINE_SOON,
                LocalDate.of(2026, 10, 20),
                "DEADLINE_SOON:1:D-3:2026-10-23",
                Map.of(),
                false,
                null,
                now
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알림을 억제한 이벤트에는 억제 사유가 필요합니다.");
    }

    @Test
    void 재시도를_예약하면_횟수와_다음_시각이_변경된다() {
        Opportunity opportunity = opportunity();
        OpportunityVersion version = version(opportunity);
        Instant now = Instant.parse("2026-10-20T02:00:00Z");

        OpportunityEvent event = OpportunityEvent.create(
                opportunity,
                version,
                OpportunityEventType.APPLICATION_OPENED,
                LocalDate.of(2026, 10, 20),
                "APPLICATION_OPENED:1:2026-10-20",
                Map.of(),
                true,
                null,
                now
        );

        event.scheduleRetry(
                now,
                Duration.ofMinutes(5),
                "임시 오류"
        );

        assertThat(event.getProcessingStatus())
                .isEqualTo(
                        OpportunityEventProcessingStatus.PENDING
                );
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextRetryAt())
                .isEqualTo(now.plus(Duration.ofMinutes(5)));
        assertThat(event.getLastError()).isEqualTo("임시 오류");
    }

    @Test
    void 다른_정책의_버전으로는_이벤트를_생성할_수_없다() {
        Opportunity opportunity = opportunity();
        Opportunity other = opportunity();
        OpportunityVersion otherVersion = version(other);
        Instant now = Instant.parse("2026-10-20T02:00:00Z");

        assertThatThrownBy(() -> OpportunityEvent.create(
                opportunity,
                otherVersion,
                OpportunityEventType.APPLICATION_OPENED,
                LocalDate.of(2026, 10, 20),
                "APPLICATION_OPENED:1:2026-10-20",
                Map.of(),
                true,
                null,
                now
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정책 버전이 해당 정책에 속하지 않습니다.");
    }

    private Opportunity opportunity() {
        return Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY)
                .title("청년 지원 정책")
                .build();
    }

    private OpportunityVersion version(
            Opportunity opportunity
    ) {
        return OpportunityVersion.builder()
                .opportunity(opportunity)
                .versionNo(1)
                .normalizedSnapshot(Map.of())
                .normalizationVersion("v1")
                .build();
    }
}