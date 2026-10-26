package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.service.EligibilityEvaluator.Judgement;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.model.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Builds a request with compact decision evidence, not raw profile fields or a copy of the result cache. */
@Component
public class EligibilityNotificationMessageFactory {
    public Optional<OutboxEvent> create(OpportunityEvent event, EligibilityNotificationRevalidation assessment, Instant now) {
        if (assessment.outcome() != EligibilityNotificationRevalidation.Outcome.CANDIDATE) {
            throw new IllegalArgumentException("Fresh candidate evidence is required");
        }
        var source = assessment.sourceComparison(); var fresh = assessment.freshComparison();
        if (!event.getOpportunityVersion().getId().equals(source.toVersionId())) {
            throw new IllegalArgumentException("Source event and compared version disagree");
        }
        var expiresAt = event.getOccurredAt().plus(Duration.ofDays(7));
        if (!now.isBefore(expiresAt)) return Optional.empty();
        String title = switch (assessment.decision()) {
            case BECAME_ELIGIBLE -> "저장한 정책의 신청 조건을 충족하게 되었습니다.";
            case ELIGIBILITY_DOWNGRADED -> "저장한 정책의 신청 조건이 변경되었습니다.";
            case ELIGIBILITY_REVIEW_REQUIRED -> "저장한 정책의 신청 조건을 추가로 확인해 주세요.";
            default -> throw new IllegalArgumentException("Unsupported eligibility notification decision");
        };
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mappingPolicyVersion", assessment.mappingPolicyVersion());
        evidence.put("fromVersionId", fresh.fromVersionId()); evidence.put("toVersionId", fresh.toVersionId());
        evidence.put("revalidatedVersionId", assessment.revalidatedVersionId());
        evidence.put("profileVersion", fresh.profileVersion()); evidence.put("ruleVersion", fresh.ruleVersion());
        evidence.put("evaluatedOn", fresh.evaluatedOn().toString());
        evidence.put("before", judgement(fresh.before())); evidence.put("after", judgement(fresh.after()));
        evidence.put("latest", judgement(assessment.latestJudgement()));
        evidence.put("sourceProfileVersion", source.profileVersion()); evidence.put("sourceRuleVersion", source.ruleVersion());
        evidence.put("sourceEvaluatedOn", source.evaluatedOn().toString());
        evidence.put("sourceBefore", judgement(source.before())); evidence.put("sourceAfter", judgement(source.after()));
        var messageId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1); envelope.put("messageId", messageId.toString());
        envelope.put("eventType", "NOTIFICATION_REQUESTED"); envelope.put("sourceEventId", event.getEventId().toString());
        envelope.put("correlationId", event.getCorrelationId().toString()); envelope.put("causationId", event.getEventId().toString());
        envelope.put("userId", source.userId()); envelope.put("opportunityId", event.getOpportunity().getId());
        envelope.put("opportunityVersionId", source.toVersionId()); envelope.put("type", assessment.decision().name());
        envelope.put("channels", assessment.decision().requestedChannels()); envelope.put("title", title);
        envelope.put("content", "변경된 신청 조건과 공식 공고를 확인해 주세요.");
        envelope.put("link", "/opportunities/" + event.getOpportunity().getId());
        envelope.put("occurredAt", event.getOccurredAt().toString()); envelope.put("expiresAt", expiresAt.toString());
        envelope.put("payload", evidence);
        return Optional.of(OutboxEvent.notification(messageId, event.getEventId(), source.userId(),
                event.getOpportunity().getId(), assessment.decision().name(), envelope, now));
    }

    private Map<String, Object> judgement(Judgement value) {
        return Map.of("status", value.evaluation().status().name(),
                "reason", Objects.requireNonNullElse(value.evaluation().reason(), ""),
                "applicationStatus", Objects.requireNonNull(value.applicationStatus()).name());
    }
}
