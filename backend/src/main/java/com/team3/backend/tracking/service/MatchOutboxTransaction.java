package com.team3.backend.tracking.service;

import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.tracking.config.*;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.model.MatchObservation;
import com.team3.backend.tracking.domain.repository.MatchCandidateEventRepository;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

/** Transactional commands shared by explicit callers and the automatic worker; delivery remains with the publisher. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="yon.tracking.match.outbox.enabled",havingValue="true")
public class MatchOutboxTransaction {
    private final MatchOutboxConfig.Ready ready;
    private final MatchOutboxProperties limits;
    private final MatchTrackingProperties settings;
    private final MatchCandidateEventRepository candidates;
    private final OutboxEventRepository outboxes;
    private final UserRepository users;
    private final OpportunityRepository policies;
    private final UserOpportunityPreferenceRepository preferences;
    private final MatchObservationTransaction observations;
    private final MatchNotificationMessageFactory messages;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final Clock clock;

    public enum Status { ENQUEUED, DUPLICATE, EMPTY, DEFERRED, SKIPPED, USER_UNAVAILABLE }
    public record Result(Status status,Long outboxId,int members) { }

    /** runId and expiry are supplied by the logical summary run, never generated anew on retry. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Result summarize(long userId,UUID runId,Instant expiresAt) {
        if (userId<1 || runId==null || expiresAt==null) throw new IllegalArgumentException("User, stable run ID and expiry required");
        var existing=existing(userId,runId,"NEW_MATCH");
        if (existing!=null) return existing;
        // Bound this run, not the user's backlog. Older candidates progress first; the remainder stays pending.
        // The automatic worker's stable daily run ID prevents a second summary on the same day.
        var cohort=candidates.findByPairUserIdAndReasonAndStatusOrderById(userId,MatchPairState.Reason.FIRST_MATCH,
                "PENDING",PageRequest.of(0,limits.maxSummaryCandidates()));
        if (cohort.isEmpty()) return result(Status.EMPTY);
        // Lock every parent policy before the user, including policies that no longer have a current version.
        for (long id:cohort.stream().map(c->c.getPair().getOpportunity().getId()).distinct().sorted().toList()) {
            jdbc.queryForList("SELECT id FROM opportunities WHERE id=? FOR SHARE",Long.class,id);
            policies.lockForNotification(id); // Also lock each joined current version before taking the user mutex.
        }
        if (users.findByIdForUpdate(userId).isEmpty()) return result(Status.USER_UNAVAILABLE);
        existing=existing(userId,runId,"NEW_MATCH");
        if (existing!=null) return existing;
        Instant now=clock.instant(); LocalDate date=LocalDate.ofInstant(now,KST);
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("Summary expiry must be in the future");
        var members=new ArrayList<MatchNotificationMessageFactory.Member>();
        var excluded=new ArrayList<MatchCandidateEvent>();
        for (var candidate:cohort) {
            em.refresh(candidate,LockModeType.PESSIMISTIC_WRITE);
            if (!"PENDING".equals(candidate.getStatus())) continue;
            var current=observations.current(userId,candidate.getPair().getOpportunity().getId());
            // Do not consume any part of a summary while another member awaits fresh observation/results.
            if (!current.available()) return result(Status.DEFERRED);
            em.refresh(candidate.getPair());
            var input=current.input();
            if (!Objects.equals(candidate.getPair().getLastInputKey(),input.inputKey(settings.possibly())))
                return result(Status.DEFERRED);
            if (changedRules(candidate,input) || input.qualify(settings.possibly())==MatchObservation.Qualification.NONE)
                excluded.add(candidate);
            else members.add(new MatchNotificationMessageFactory.Member(candidate,input));
        }
        checkDate(date);
        for (var candidate:excluded) candidate.skip("NO_LONGER_QUALIFIED_OR_RULE_CHANGED",now);
        if (members.isEmpty()) return result(Status.EMPTY);
        var outbox=outboxes.saveAndFlush(messages.summary(userId,runId,members,settings,now,expiresAt));
        for (var member:members) member.candidate().enqueue(outbox.getId(),now);
        candidates.flush(); checkDate(date);
        return new Result(Status.ENQUEUED,outbox.getId(),members.size());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Result transition(long userId,long candidateId) {
        if (userId<1 || candidateId<1) throw new IllegalArgumentException("Persisted IDs required");
        var found=candidates.findById(candidateId);
        if (found.isEmpty()) return result(Status.EMPTY);
        var candidate=found.orElseThrow();
        if (!Objects.equals(candidate.getPair().getUser().getId(),userId) || candidate.getReason()!=MatchPairState.Reason.TRANSITION)
            throw new IllegalArgumentException("Owned transition required");
        long policyId=candidate.getPair().getOpportunity().getId();
        jdbc.queryForList("SELECT id FROM opportunities WHERE id=? FOR SHARE",Long.class,policyId);
        policies.lockForNotification(policyId);
        if (users.findByIdForUpdate(userId).isEmpty()) return result(Status.USER_UNAVAILABLE);
        var existing=existing(userId,candidate.getEventId(),"BECAME_ELIGIBLE");
        if (existing!=null) return existing;
        em.refresh(candidate,LockModeType.PESSIMISTIC_WRITE);
        if (!"PENDING".equals(candidate.getStatus())) return result(Status.SKIPPED);
        var current=observations.current(userId,policyId);
        if (!current.available()) return result(Status.DEFERRED);
        em.refresh(candidate.getPair());
        var input=current.input(); Instant now=clock.instant();
        if (!Objects.equals(candidate.getPair().getLastInputKey(),input.inputKey(settings.possibly()))) return result(Status.DEFERRED);
        var trigger=candidate.getEvidence().get("trigger");
        if (input.saved() && (trigger==null || ("POLICY_CHANGED".equals(trigger)
                && preferences.lockSavedBeforeVersionForNotification(policyId,
                    ((Number)candidate.getEvidence().get("versionNo")).intValue(),List.of(userId)).isEmpty()))) {
            candidate.skip("NOT_SAVED_BEFORE_SOURCE_VERSION",now); return result(Status.SKIPPED);
        }
        // A newer observation invalidates an old transition, even if the user is eligible again now.
        if (changedRules(candidate,input) || candidate.getOccurrenceNo()!=candidate.getPair().getTransitionCount()
                || input.qualify(settings.possibly())!=MatchObservation.Qualification.ELIGIBLE) {
            candidate.skip("SUPERSEDED_OR_NO_LONGER_ELIGIBLE",now); return result(Status.SKIPPED);
        }
        var message=messages.transition(userId,new MatchNotificationMessageFactory.Member(candidate,input),settings,now);
        if (message.isEmpty()) { candidate.skip("EXPIRED",now); return result(Status.SKIPPED); }
        var outbox=outboxes.saveAndFlush(message.orElseThrow()); candidate.enqueue(outbox.getId(),now);
        candidates.flush(); checkDate(input.evaluatedOn());
        return new Result(Status.ENQUEUED,outbox.getId(),1);
    }

    private boolean changedRules(MatchCandidateEvent candidate,MatchObservation input) {
        return !Objects.equals(candidate.getEvidence().get("ruleVersion"),input.ruleVersion())
                || !Objects.equals(candidate.getEvidence().get("normalizationVersion"),input.normalizationVersion());
    }
    private Result existing(long userId,UUID source,String type) {
        var previous=outboxes.findByDedupKey(OutboxEvent.notificationDedupKey(source,userId));
        if (previous.isEmpty()) return null;
        var outbox=previous.orElseThrow();
        if (!type.equals(outbox.getNotificationType())) throw new IllegalArgumentException("Source ID already used by another notification type");
        return new Result(Status.DUPLICATE,outbox.getId(),0);
    }
    private Result result(Status status) { return new Result(status,null,0); }
    private void checkDate(LocalDate date) {
        if (!date.equals(LocalDate.ofInstant(clock.instant(),KST))) throw new IllegalStateException("Match date changed; retry");
    }
}
