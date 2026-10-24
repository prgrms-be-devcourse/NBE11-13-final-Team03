package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.service.EligibilityEvaluator.Judgement;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.tracking.config.EventFanoutProperties;
import com.team3.backend.tracking.domain.entity.*;
import com.team3.backend.tracking.domain.model.*;
import com.team3.backend.tracking.domain.repository.*;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;
import static com.team3.backend.tracking.domain.entity.EligibilityEventAssessment.Status.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"yon.tracking.eligibility-fanout.enabled", "yon.tracking.eligibility-outbox.enabled"}, havingValue = "true")
public class EligibilityFanoutBatch {
    private final OpportunityEventRepository events;
    private final OpportunityRepository opportunities;
    private final OpportunityVersionRepository versions;
    private final UserOpportunityPreferenceRepository preferences;
    private final UserRepository users;
    private final EligibilityEventAssessmentRepository assessments;
    private final EligibilityVersionComparisonService comparisons;
    private final ProvisionalEligibilityTransitionClassifier classifier;
    private final EligibilityOutboxTransaction outboxes;
    private final EventFanoutProperties properties;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** User evidence, Outbox and cursor/heartbeat are one page transaction; prior pages remain committed. */
    @Transactional
    public boolean process(EventFanoutExecution execution) {
        var event = events.lockForFanout(execution.eventId()).orElseThrow();
        if (event.getEventType() != OpportunityEventType.ELIGIBILITY_CHANGED || !event.ownedBy(execution.leaseToken())) {
            throw new IllegalStateException("Eligibility event ownership lost or wrong event type");
        }
        var now = clock.instant();
        if (!event.isNotificationEligible()) { event.skipFanout(now, "EVENT_SUPPRESSED"); return true; }
        if (!now.isBefore(event.getOccurredAt().plus(Duration.ofDays(7)))) { event.skipFanout(now, "EXPIRED"); return true; }
        var policyResult = opportunities.lockForNotification(event.getOpportunity().getId());
        if (policyResult.isEmpty()) { event.skipFanout(now, "POLICY_UNAVAILABLE"); return true; }
        var policy = policyResult.orElseThrow(); em.refresh(policy);
        var to = event.getOpportunityVersion();
        if (policy.getOpportunityType() != OpportunityType.YOUTH_POLICY
                || !to.getOpportunity().getId().equals(policy.getId())
                || policy.getCurrentVersion().getVersionNo() < to.getVersionNo()) {
            event.skipFanout(now, "POLICY_UNAVAILABLE"); return true;
        }
        long fromId = versionId(event.getPayload().get("fromVersionId"));
        if (versionId(event.getPayload().get("toVersionId")) != to.getId()) throw new IllegalArgumentException("Event version pair mismatch");
        var from = versions.findById(fromId).orElseThrow();
        if (!from.getOpportunity().getId().equals(policy.getId()) || from.getVersionNo() >= to.getVersionNo()
                || versions.findFirstByOpportunityIdAndVersionNoLessThanOrderByVersionNoDesc(policy.getId(), to.getVersionNo())
                    .filter(v -> v.getId().equals(from.getId())).isEmpty()) throw new IllegalArgumentException("Immediate same-policy predecessor required");
        if (!Objects.equals(from.getNormalizationVersion(), to.getNormalizationVersion())
                || !Objects.equals(to.getNormalizationVersion(), policy.getCurrentVersion().getNormalizationVersion())) {
            event.skipFanout(now, "NORMALIZATION_RULE_CHANGED"); return true;
        }
        var date = LocalDate.ofInstant(now, KST);
        var ids = preferences.findSavedUserIdsAfter(policy.getId(), event.getLastUserId(), PageRequest.of(0, properties.batchSize()));
        if (ids.isEmpty()) {
            if (assessments.existsByEventIdAndStatus(event.getId(), DEFERRED)) event.waitForPolicy("ELIGIBILITY_ASSESSMENT_DEFERRED");
            else event.complete(now);
            return true;
        }
        for (Long userId : ids) {
            // Ascending user mutexes, then saved row, then profile: matches explicit Outbox/withdrawal lock order.
            var userResult = users.findByIdForUpdate(userId);
            if (userResult.isEmpty()) continue; // Withdrawal deleted the user; do not recreate personal evidence.
            var user = userResult.orElseThrow();
            if (assessments.findByEventIdAndUserId(event.getId(), userId).isPresent()) continue;
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("mappingPolicyVersion", ProvisionalEligibilityTransitionClassifier.POLICY_VERSION);
            evidence.put("fromVersionId", fromId); evidence.put("toVersionId", to.getId());
            evidence.put("observedCurrentVersionId", policy.getCurrentVersion().getId());
            evidence.put("evaluatedOn", date.toString());
            var status = SKIPPED; String reason; Long outboxId = null;
            if (preferences.lockSavedForNotification(policy.getId(), List.of(userId)).isEmpty()) reason = "NOT_SAVED";
            else {
                var profileIds = jdbc.queryForList("SELECT id FROM eligibility_profiles WHERE user_id=? FOR SHARE", Long.class, userId);
                if (profileIds.isEmpty()) { status = DEFERRED; reason = "PROFILE_MISSING"; }
                else {
                    em.refresh(em.find(EligibilityProfile.class, profileIds.getFirst()));
                    var source = comparisons.compare(userId, from, to, date).orElseThrow();
                    evidence.put("source", comparisonEvidence(source));
                    var decision = classifier.classify(source.before().evaluation().status(), source.after().evaluation().status());
                    evidence.put("decision", decision.name());
                    if (decision == EligibilityTransitionDecision.DEFERRED) { status = DEFERRED; reason = "MAPPING_DEFERRED"; }
                    else if (decision == EligibilityTransitionDecision.NO_CHANGE) { status = NO_CHANGE; reason = "NO_CHANGE"; }
                    else {
                        var result = outboxes.create(event.getId(), source);
                        reason = result.status().name(); outboxId = result.outboxId();
                        status = switch (result.status()) {
                            case CREATED -> OUTBOX_CREATED;
                            case EXISTING -> OUTBOX_EXISTING;
                            default -> SKIPPED;
                        };
                        var fresh = result.evidence();
                        if (fresh != null) {
                            evidence.put("revalidationOutcome", fresh.outcome().name());
                            if (fresh.freshComparison() != null) {
                                evidence.put("fresh", comparisonEvidence(fresh.freshComparison()));
                                evidence.put("revalidatedVersionId", fresh.revalidatedVersionId());
                                evidence.put("latest", judgement(fresh.latestJudgement()));
                            }
                            boolean pending = fresh.outcome() == EligibilityNotificationRevalidation.Outcome.PROFILE_MISSING
                                    || fresh.outcome() == EligibilityNotificationRevalidation.Outcome.RULE_VERSION_CHANGED
                                    || (fresh.freshComparison() != null && classifier.classify(fresh.freshComparison().before().evaluation().status(),
                                            fresh.freshComparison().after().evaluation().status()) == EligibilityTransitionDecision.DEFERRED)
                                    || (fresh.latestJudgement() != null && fresh.latestJudgement().evaluation().status() == EligibilityStatus.UNKNOWN);
                            if (pending) { status = DEFERRED; reason = fresh.outcome().name(); }
                        }
                    }
                }
            }
            assessments.save(EligibilityEventAssessment.record(event, user, status, reason, outboxId, evidence, clock.instant()));
        }
        if (!LocalDate.ofInstant(clock.instant(), KST).equals(date)) throw new IllegalStateException("Assessment date changed; retry page");
        assessments.flush();
        event.advanceFanout(ids.getLast(), clock.instant());
        return false;
    }

    private long versionId(Object value) {
        if (value == null) throw new IllegalArgumentException("Source version ID required");
        try { long id = Long.parseLong(value.toString()); if (id > 0) return id; }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid source version ID", failure); }
        throw new IllegalArgumentException("Positive version ID required");
    }
    private Map<String, Object> comparisonEvidence(EligibilityVersionComparison value) {
        return Map.of("profileVersion", value.profileVersion(), "ruleVersion", value.ruleVersion(), "evaluatedOn", value.evaluatedOn().toString(),
                "before", judgement(value.before()), "after", judgement(value.after()));
    }
    private Map<String, Object> judgement(Judgement value) {
        return Map.of("status", value.evaluation().status().name(), "reason", Objects.requireNonNullElse(value.evaluation().reason(), ""),
                "applicationStatus", value.applicationStatus().name());
    }
}
