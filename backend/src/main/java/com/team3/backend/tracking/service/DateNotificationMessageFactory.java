package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

/** Builds a complete per-user envelope; performs no HTTP/SQS call or user eligibility calculation. */
@Component
@RequiredArgsConstructor
public class DateNotificationMessageFactory {
    private final ApplicationStatusCalculator statusCalculator;
    private final DeadlineSoonDetector deadlines;

    public boolean supports(OpportunityEventType type) {
        return type == OpportunityEventType.APPLICATION_OPENED || type == OpportunityEventType.APPLICATION_CLOSED
                || type == OpportunityEventType.DEADLINE_SOON;
    }

    public Optional<Prepared> prepare(OpportunityEvent event, Opportunity current,
                                      List<ApplicationPeriod> periods, Instant now) {
        if (!supports(event.getEventType())) throw new IllegalArgumentException("Unsupported event type");
        if (!event.isNotificationEligible() || current.getOpportunityType() != OpportunityType.YOUTH_POLICY) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.ofInstant(now, KST);
        var status = statusCalculator.calculate(periods, today);
        var fixed = periods.stream().filter(p -> p.validationStatus() == ValidationStatus.VALID
                && p.periodType() == PeriodType.FIXED).toList();
        var payload = new LinkedHashMap<String, Object>(event.getPayload());
        Instant expiresAt;
        String title;
        List<String> channels;
        switch (event.getEventType()) {
            case DEADLINE_SOON -> {
                var end = LocalDate.parse(required(event, "applicationEndDate"));
                String threshold = required(event, "threshold");
                if (!List.of("D-7", "D-3", "D-1").contains(threshold)) {
                    throw new IllegalArgumentException("Invalid deadline threshold");
                }
                boolean stillRelevant = event.getEventDate().equals(today) && deadlines.detect(periods, today).stream()
                        .anyMatch(c -> c.endDate().equals(end) && c.threshold().code().equals(threshold));
                if (!stillRelevant) return Optional.empty();
                expiresAt = end.plusDays(1).atStartOfDay(KST).toInstant();
                title = switch (threshold) {
                    case "D-7" -> "신청 마감이 7일 남았습니다.";
                    case "D-3" -> "신청 마감이 3일 남았습니다.";
                    case "D-1" -> "신청 마감이 1일 남았습니다.";
                    default -> throw new IllegalArgumentException("Invalid deadline threshold");
                };
                channels = List.of("IN_APP", "PUSH");
            }
            case APPLICATION_OPENED -> {
                var start = LocalDate.parse(required(event, "startDate"));
                var matching = fixed.stream().filter(p -> start.equals(p.startDate())
                        && !today.isBefore(start) && (p.endDate() == null || !today.isAfter(p.endDate()))).toList();
                if (status != ApplicationStatus.OPEN || matching.isEmpty()) return Optional.empty();
                expiresAt = event.getOccurredAt().plus(Duration.ofDays(3));
                // One earlier period ending does not close the whole policy.
                var end = matching.stream().anyMatch(p -> p.endDate() == null) ? Optional.<LocalDate>empty()
                        : matching.stream().map(ApplicationPeriod::endDate).max(Comparator.naturalOrder());
                if (end.isPresent()) {
                    var deadline = end.orElseThrow().plusDays(1).atStartOfDay(KST).toInstant();
                    if (deadline.isBefore(expiresAt)) expiresAt = deadline;
                    payload.put("applicationEndDate", end.orElseThrow().toString());
                }
                title = "저장한 정책의 신청이 시작되었습니다.";
                channels = List.of("IN_APP", "PUSH");
            }
            case APPLICATION_CLOSED -> {
                var end = LocalDate.parse(required(event, "endDate"));
                if (status != ApplicationStatus.CLOSED
                        || fixed.stream().noneMatch(p -> end.equals(p.endDate()))) return Optional.empty();
                expiresAt = event.getOccurredAt().plus(Duration.ofDays(3));
                payload.put("applicationEndDate", end.toString());
                title = "저장한 정책의 신청이 마감되었습니다.";
                channels = List.of("IN_APP");
            }
            default -> throw new IllegalArgumentException("Unsupported event type");
        }
        return now.isBefore(expiresAt)
                ? Optional.of(new Prepared(title, channels, expiresAt, Map.copyOf(payload))) : Optional.empty();
    }

    public OutboxEvent create(OpportunityEvent event, Long userId, Prepared prepared, Instant now) {
        if (!now.isBefore(prepared.expiresAt())) throw new IllegalArgumentException("Message expired");
        UUID messageId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("messageId", messageId.toString());
        envelope.put("eventType", "NOTIFICATION_REQUESTED");
        envelope.put("sourceEventId", event.getEventId().toString());
        envelope.put("correlationId", event.getCorrelationId().toString());
        envelope.put("causationId", event.getEventId().toString());
        envelope.put("userId", userId);
        envelope.put("opportunityId", event.getOpportunity().getId());
        envelope.put("opportunityVersionId", event.getOpportunityVersion().getId());
        envelope.put("type", event.getEventType().name());
        envelope.put("channels", prepared.channels());
        envelope.put("title", prepared.title());
        envelope.put("content", "저장한 정책의 신청 기간을 확인해 주세요.");
        envelope.put("link", "/opportunities/" + event.getOpportunity().getId());
        envelope.put("occurredAt", event.getOccurredAt().toString());
        envelope.put("expiresAt", prepared.expiresAt().toString());
        envelope.put("payload", prepared.payload());
        return OutboxEvent.notification(messageId, event.getEventId(), userId, event.getOpportunity().getId(),
                event.getEventType().name(), envelope, now);
    }

    private String required(OpportunityEvent event, String key) {
        Object value = event.getPayload().get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing event payload: " + key);
        }
        return text;
    }

    public record Prepared(String title, List<String> channels, Instant expiresAt, Map<String, Object> payload) {
    }
}
