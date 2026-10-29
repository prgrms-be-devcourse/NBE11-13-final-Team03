package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import com.team3.backend.tracking.domain.model.*;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;
import static com.team3.backend.opportunity.domain.status.ApplicationStatus.*;

/** Per-user revalidation/write path, reused by the separately opted-in eligibility consumer. */
@Service
@ConditionalOnProperty(name = "yon.tracking.eligibility-outbox.enabled", havingValue = "true")
@RequiredArgsConstructor
public class EligibilityOutboxTransaction {
    private final OpportunityEventRepository events;
    private final OpportunityRepository opportunities;
    private final UserRepository users;
    private final UserOpportunityPreferenceRepository preferences;
    private final OutboxEventRepository outboxes;
    private final EligibilityNotificationRevalidationService revalidation;
    private final EligibilityNotificationMessageFactory messages;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Value("${yon.tracking.match.outbox.enabled:false}")
    private boolean matchOwnsPositiveTransitions;

    public enum Status { CREATED, EXISTING, SOURCE_SUPPRESSED, POLICY_UNAVAILABLE, USER_UNAVAILABLE,
        NOT_SAVED, SAVED_VERSION_NOT_BEFORE_EVENT, PROFILE_MISSING, REVALIDATION_REJECTED, POLICY_NOT_RELEVANT, EXPIRED,
        MATCH_ROUTE_OWNS_TRANSITION }
    public record Result(Status status, Long outboxId, EligibilityNotificationRevalidation evidence) { }

    @Transactional
    public Result create(Long eventId, EligibilityVersionComparison source) {
        Objects.requireNonNull(eventId, "eventId"); Objects.requireNonNull(source, "source");
        if (source.userId() == null || source.userId() <= 0) throw new IllegalArgumentException("A user ID is required");
        var event = events.lockForFanout(eventId).orElseThrow();
        if (event.getEventType() != OpportunityEventType.ELIGIBILITY_CHANGED
                || !Objects.equals(event.getOpportunityVersion().getId(), source.toVersionId())
                || !Objects.equals(versionId(event.getPayload().get("fromVersionId")), source.fromVersionId())
                || !Objects.equals(versionId(event.getPayload().get("toVersionId")), source.toVersionId())) {
            throw new IllegalArgumentException("Source event must identify the compared eligibility version pair");
        }
        if (!event.isNotificationEligible()) return new Result(Status.SOURCE_SUPPRESSED, null, null);
        var policyResult = opportunities.lockForNotification(event.getOpportunity().getId());
        if (policyResult.isEmpty()) return new Result(Status.POLICY_UNAVAILABLE, null, null);
        var policy = policyResult.orElseThrow(); em.refresh(policy);
        // Same user mutex as withdrawal/preference writes; also serializes duplicate explicit requests.
        if (users.findByIdForUpdate(source.userId()).isEmpty()) return new Result(Status.USER_UNAVAILABLE, null, null);
        var key = OutboxEvent.notificationDedupKey(event.getEventId(), source.userId());
        var existing = outboxes.findByDedupKey(key);
        if (existing.isPresent()) return new Result(Status.EXISTING, existing.orElseThrow().getId(), null);
        if (preferences.lockSavedForNotification(policy.getId(), List.of(source.userId())).isEmpty()) {
            return new Result(Status.NOT_SAVED, null, null);
        }
        if (preferences.lockSavedBeforeVersionForNotification(policy.getId(), event.getOpportunityVersion().getVersionNo(),
                List.of(source.userId())).isEmpty()) {
            return new Result(Status.SAVED_VERSION_NOT_BEFORE_EVENT, null, null);
        }
        var profileIds = jdbc.queryForList("SELECT id FROM eligibility_profiles WHERE user_id = ? FOR SHARE", Long.class, source.userId());
        if (profileIds.isEmpty()) return new Result(Status.PROFILE_MISSING, null, null);
        em.refresh(em.find(EligibilityProfile.class, profileIds.getFirst()));
        var date = LocalDate.ofInstant(clock.instant(), KST);
        var result = revalidation.revalidate(source, date);
        if (result.outcome() != EligibilityNotificationRevalidation.Outcome.CANDIDATE) {
            return new Result(Status.REVALIDATION_REJECTED, null, result);
        }
        // source.toVersionId must be owned by the event's policy, not merely by a self-consistent other pair.
        if (!Objects.equals(policy.getCurrentVersion().getId(), result.revalidatedVersionId())) {
            throw new IllegalArgumentException("Revalidated policy does not match the locked source policy");
        }
        if (matchOwnsPositiveTransitions && result.decision()==EligibilityTransitionDecision.BECAME_ELIGIBLE) {
            // New positive requests belong to the pair/occurrence route; saved loss/review remain IN_APP here.
            return new Result(Status.MATCH_ROUTE_OWNS_TRANSITION,null,result);
        }
        var applicationStatus = result.latestJudgement().applicationStatus();
        // Conservative provisional guard: an already-closed or unknown application period is not a live candidate.
        if (applicationStatus != OPEN && applicationStatus != ALWAYS_OPEN && applicationStatus != UPCOMING) {
            return new Result(Status.POLICY_NOT_RELEVANT, null, result);
        }
        var now = clock.instant();
        if (!LocalDate.ofInstant(now, KST).equals(date)) throw new IllegalStateException("Revalidation date changed; retry transaction");
        var message = messages.create(event, result, now);
        if (message.isEmpty()) return new Result(Status.EXPIRED, null, result);
        var saved = outboxes.saveAndFlush(message.orElseThrow());
        return new Result(Status.CREATED, saved.getId(), result);
    }

    private Long versionId(Object value) {
        if (value == null) throw new IllegalArgumentException("Source version ID is required");
        try { return Long.valueOf(value.toString()); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid source version ID", failure); }
    }
}
