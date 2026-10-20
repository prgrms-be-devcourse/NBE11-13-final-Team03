package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEventSuppressionReason;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import com.team3.backend.tracking.domain.model.ApplicationStatusTransition;
import com.team3.backend.tracking.domain.model.DeadlineSoonCandidate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

@Component
public class OpportunityEventFactory {

    public OpportunityEvent createStatusTransitionEvent(
            Opportunity opportunity,
            OpportunityVersion opportunityVersion,
            ApplicationStatusTransition transition,
            Instant now
    ) {
        Objects.requireNonNull(transition, "transition");

        Long opportunityId = requireOpportunityId(opportunity);

        return switch (transition.eventType()) {
            case APPLICATION_OPENED -> OpportunityEvent.create(
                    opportunity,
                    opportunityVersion,
                    OpportunityEventType.APPLICATION_OPENED,
                    transition.eventDate(),
                    openedDedupKey(
                            opportunityId,
                            transition.eventDate()
                    ),
                    Map.of(
                            "previousStatus",
                            transition.previousStatus().name(),
                            "currentStatus",
                            transition.currentStatus().name(),
                            "startDate",
                            transition.eventDate().toString()
                    ),
                    true,
                    null,
                    now
            );

            case APPLICATION_CLOSED -> {
                LocalDate endDate =
                        transition.eventDate().minusDays(1);

                yield OpportunityEvent.create(
                        opportunity,
                        opportunityVersion,
                        OpportunityEventType.APPLICATION_CLOSED,
                        endDate,
                        closedDedupKey(
                                opportunityId,
                                endDate
                        ),
                        Map.of(
                                "previousStatus",
                                transition.previousStatus().name(),
                                "currentStatus",
                                transition.currentStatus().name(),
                                "endDate",
                                endDate.toString(),
                                "detectedDate",
                                transition.eventDate().toString()
                        ),
                        true,
                        null,
                        now
                );
            }

            default -> throw new IllegalArgumentException(
                    "지원하지 않는 모집 상태 변화 이벤트입니다: "
                            + transition.eventType()
            );
        };
    }

    public OpportunityEvent createDeadlineSoonEvent(
            Opportunity opportunity,
            OpportunityVersion opportunityVersion,
            DeadlineSoonCandidate candidate,
            LocalDate processingDate,
            Instant now
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(processingDate, "processingDate");

        if (candidate.eventDate().isAfter(processingDate)) {
            throw new IllegalArgumentException(
                    "미래 기준일의 마감 임박 이벤트는 생성할 수 없습니다."
            );
        }

        Long opportunityId = requireOpportunityId(opportunity);

        boolean notificationEligible =
                candidate.eventDate().isEqual(processingDate);

        OpportunityEventSuppressionReason suppressionReason =
                notificationEligible
                        ? null
                        : OpportunityEventSuppressionReason
                          .THRESHOLD_ALREADY_PASSED;

        return OpportunityEvent.create(
                opportunity,
                opportunityVersion,
                OpportunityEventType.DEADLINE_SOON,
                candidate.eventDate(),
                deadlineDedupKey(
                        opportunityId,
                        candidate.threshold().code(),
                        candidate.endDate()
                ),
                Map.of(
                        "threshold",
                        candidate.threshold().code(),
                        "endDate",
                        candidate.endDate().toString()
                ),
                notificationEligible,
                suppressionReason,
                now
        );
    }

    private Long requireOpportunityId(
            Opportunity opportunity
    ) {
        Objects.requireNonNull(opportunity, "opportunity");

        if (opportunity.getId() == null) {
            throw new IllegalArgumentException(
                    "저장되지 않은 정책으로는 이벤트를 생성할 수 없습니다."
            );
        }

        return opportunity.getId();
    }

    private String openedDedupKey(
            Long opportunityId,
            LocalDate startDate
    ) {
        return "APPLICATION_OPENED:"
                + opportunityId
                + ":"
                + startDate;
    }

    private String closedDedupKey(
            Long opportunityId,
            LocalDate endDate
    ) {
        return "APPLICATION_CLOSED:"
                + opportunityId
                + ":"
                + endDate;
    }

    private String deadlineDedupKey(
            Long opportunityId,
            String threshold,
            LocalDate endDate
    ) {
        return "DEADLINE_SOON:"
                + opportunityId
                + ":"
                + threshold
                + ":"
                + endDate;
    }
}