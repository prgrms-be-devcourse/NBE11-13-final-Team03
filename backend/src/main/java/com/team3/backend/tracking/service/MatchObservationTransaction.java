package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import com.team3.backend.tracking.domain.entity.MatchCandidateEvent;
import com.team3.backend.tracking.domain.entity.MatchPairState;
import com.team3.backend.tracking.domain.model.MatchObservation;
import com.team3.backend.tracking.domain.repository.MatchCandidateEventRepository;
import com.team3.backend.tracking.domain.repository.MatchPairStateRepository;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

/** Rechecks current source data, records pair state and pending intent atomically. No sender/Outbox call. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="yon.tracking.match.enabled",havingValue="true")
public class MatchObservationTransaction {
    private final OpportunityRepository policies;
    private final UserRepository users;
    private final UserOpportunityPreferenceRepository preferences;
    private final ApplicationWindowRepository windows;
    private final ApplicationStatusCalculator applicationStatus;
    private final MatchPairStateRepository pairs;
    private final MatchCandidateEventRepository candidates;
    private final MatchTrackingProperties settings;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final Clock clock;

    public enum Status { RECORDED, DUPLICATE, CANDIDATE_CREATED, USER_UNAVAILABLE, POLICY_UNAVAILABLE, PROFILE_MISSING, RESULT_STALE_OR_MISSING }
    public record Result(Status status, Long pairId, Long candidateId, MatchPairState.Reason reason) { }
    private record Profile(long id,long version) { }
    private record FreshResult(long profileVersion,String rule,EligibilityStatus status,int missing,int check,LocalDate validUntil) { }
    public record Current(Status rejection,User user,Opportunity policy,MatchObservation input) {
        public boolean available() { return input!=null; }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Result observe(long userId,long opportunityId) {
        var current=current(userId,opportunityId);
        if (!current.available()) return empty(current.rejection());
        var input=current.input();
        var pair=pairs.findByUserIdAndOpportunityId(userId,opportunityId)
                .orElseGet(()->MatchPairState.forPair(current.user(),current.policy()));
        if (input.inputKey(settings.possibly()).equals(pair.getLastInputKey())) return new Result(Status.DUPLICATE,pair.getId(),null,null);
        var notice=pair.observe(input,settings.possibly(),clock.instant());
        if (notice!=null && notice.reason()==MatchPairState.Reason.TRANSITION && input.saved()
                && notice.trigger()==MatchPairState.Trigger.POLICY_CHANGED
                && preferences.lockSavedBeforeVersionForNotification(opportunityId,input.versionNo(),java.util.List.of(userId)).isEmpty())
            notice=null; // Observe the new state, but do not resurrect a policy change from before the save.
        pairs.saveAndFlush(pair);
        MatchCandidateEvent candidate=null;
        if (notice!=null) candidate=candidates.saveAndFlush(MatchCandidateEvent.pending(pair,notice,input,clock.instant()));
        if (!LocalDate.ofInstant(clock.instant(),KST).equals(input.evaluatedOn())) throw new IllegalStateException("Match observation date changed; retry");
        return new Result(candidate==null?Status.RECORDED:Status.CANDIDATE_CREATED,pair.getId(),
                candidate==null?null:candidate.getId(),notice==null?null:notice.reason());
    }

    /** Read-only business operation under caller's transaction. Aggregate callers prelock all policies before the user. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Current current(long userId,long opportunityId) {
        if (userId<1 || opportunityId<1) throw new IllegalArgumentException("Persisted user/policy IDs required");
        var locked=policies.lockForNotification(opportunityId);
        if (locked.isEmpty()) return rejected(Status.POLICY_UNAVAILABLE);
        var policy=locked.orElseThrow(); em.refresh(policy);
        var version=policy.getCurrentVersion();
        if (policy.getOpportunityType()!=OpportunityType.YOUTH_POLICY || version==null
                || version.getPersonalizationStatus()!=PersonalizationStatus.PERSONALIZABLE || version.getConditionSetId()==null)
            return rejected(Status.POLICY_UNAVAILABLE);
        // Same upper lock order as existing event processing and withdrawal, before preferences/profile/result/pair.
        var user=users.findByIdForUpdate(userId);
        if (user.isEmpty()) return rejected(Status.USER_UNAVAILABLE);
        boolean saved=!preferences.lockSavedForNotification(opportunityId,java.util.List.of(userId)).isEmpty();
        var profiles=jdbc.query("SELECT id,profile_version FROM eligibility_profiles WHERE user_id=? FOR SHARE",
                (rs,row)->new Profile(rs.getLong(1),rs.getLong(2)),userId);
        if (profiles.isEmpty()) return rejected(Status.PROFILE_MISSING);
        var profile=profiles.getFirst(); var date=LocalDate.ofInstant(clock.instant(),KST);
        var results=jdbc.query("""
                SELECT profile_version,rule_version,eligibility_status,user_info_missing_count,check_required_count,valid_until
                FROM eligibility_set_results WHERE user_id=? AND condition_set_id=? FOR SHARE
                """,(rs,row)->new FreshResult(rs.getLong(1),rs.getString(2),EligibilityStatus.valueOf(rs.getString(3)),
                        rs.getInt(4),rs.getInt(5),rs.getObject(6,LocalDate.class)),userId,version.getConditionSetId());
        if (results.isEmpty()) return rejected(Status.RESULT_STALE_OR_MISSING);
        var result=results.getFirst();
        if (result.profileVersion()!=profile.version() || !Objects.equals(result.rule(),EligibilityResultService.RULE_VERSION)
                || (result.validUntil()!=null && !result.validUntil().isAfter(date))) return rejected(Status.RESULT_STALE_OR_MISSING);
        boolean interest=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM user_interests WHERE user_id=? AND
                  ((interest_type='CATEGORY' AND interest_code=?) OR (interest_type='SUB_CATEGORY' AND interest_code=?)))
                """,Boolean.class,userId,policy.getCategory(),policy.getSubCategory()));
        var periods=windows.findByVersionId(version.getId()).stream().map(w->new ApplicationPeriod(w.getPeriodType(),
                w.getStartDate(),w.getEndDate(),w.getValidationStatus(),w.getPeriodText())).toList();
        var input=new MatchObservation(version.getId(),version.getVersionNo(),profile.id(),profile.version(),result.rule(),
                version.getNormalizationVersion(),result.status(),policy.getOpportunityType(),version.getPersonalizationStatus(),
                applicationStatus.calculate(periods,date),saved,interest,result.missing(),result.check(),date);
        return new Current(null,user.orElseThrow(),policy,input);
    }
    private Result empty(Status status) { return new Result(status,null,null,null); }
    private Current rejected(Status status) { return new Current(status,null,null,null); }
}
