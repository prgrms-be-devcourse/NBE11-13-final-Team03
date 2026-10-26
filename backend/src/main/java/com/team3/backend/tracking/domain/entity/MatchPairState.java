package com.team3.backend.tracking.domain.entity;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import com.team3.backend.tracking.domain.model.MatchObservation;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Table(name="notification_match_pairs",uniqueConstraints=@UniqueConstraint(
        name="uk_match_pair_user_policy",columnNames={"user_id","opportunity_id"}))
public class MatchPairState {
    public enum Reason { FIRST_MATCH, TRANSITION }
    public enum Trigger { FIRST_OBSERVATION, PROFILE_CHANGED, POLICY_CHANGED, RESULT_CHANGED }
    public record Notice(Reason reason, long occurrence, EligibilityStatus before, MatchObservation.Qualification qualification,Trigger trigger) { }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="user_id",nullable=false,updatable=false) @OnDelete(action=OnDeleteAction.CASCADE) private User user;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="opportunity_id",nullable=false,updatable=false) private Opportunity opportunity;
    @Enumerated(EnumType.STRING) @Column(name="last_status",nullable=false,length=30) private EligibilityStatus lastStatus;
    @Column(name="ever_matched",nullable=false) private boolean everMatched;
    @Column(name="transition_count",nullable=false) private long transitionCount;
    @Column(name="last_input_key",nullable=false,length=64) private String lastInputKey;
    @Column(name="last_version_no",nullable=false) private int lastVersionNo;
    @Column(name="last_profile_id",nullable=false) private long lastProfileId;
    @Column(name="last_profile_version",nullable=false) private long lastProfileVersion;
    @Column(name="last_rule_version",nullable=false,length=80) private String lastRuleVersion;
    @Column(name="last_normalization_version",nullable=false,length=80) private String lastNormalizationVersion;
    @Column(name="observed_on",nullable=false) private LocalDate observedOn;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    public static MatchPairState forPair(User user, Opportunity policy) {
        if (user==null || user.getId()==null || policy==null || policy.getId()==null)
            throw new IllegalArgumentException("Persisted user and policy required");
        var pair=new MatchPairState(); pair.user=user; pair.opportunity=policy; return pair;
    }

    /** Caller owns policy -> user -> source locks. Pair update and any notice must commit together. */
    public Notice observe(MatchObservation input, MatchTrackingProperties.Possibly settings, Instant now) {
        Objects.requireNonNull(now);
        if (observedOn!=null && (input.evaluatedOn().isBefore(observedOn) || input.versionNo()<lastVersionNo
                || (input.profileId()==lastProfileId && input.profileVersion()<lastProfileVersion)))
            throw new IllegalArgumentException("An older observation cannot rewind match state");
        String key=input.inputKey(settings);
        if (key.equals(lastInputKey)) return null;
        var qualification=input.qualify(settings); var before=lastStatus; Notice notice=null;
        var trigger=lastInputKey==null ? Trigger.FIRST_OBSERVATION
                : input.versionNo()!=lastVersionNo ? Trigger.POLICY_CHANGED
                : input.profileId()!=lastProfileId || input.profileVersion()!=lastProfileVersion ? Trigger.PROFILE_CHANGED : Trigger.RESULT_CHANGED;
        boolean changedRules=lastRuleVersion!=null && (!lastRuleVersion.equals(input.ruleVersion())
                || !lastNormalizationVersion.equals(input.normalizationVersion()));
        if (!changedRules && qualification!=MatchObservation.Qualification.NONE) {
            if (before==EligibilityStatus.NOT_ELIGIBLE && input.status()==EligibilityStatus.ELIGIBLE) {
                notice=new Notice(Reason.TRANSITION,++transitionCount,before,qualification,trigger);
            } else if (!everMatched) notice=new Notice(Reason.FIRST_MATCH,0,before,qualification,trigger);
        }
        // Rule-only rebaselining observes state without generating an initial/transition notice.
        everMatched|=qualification!=MatchObservation.Qualification.NONE;
        lastStatus=input.status(); lastInputKey=key; lastVersionNo=input.versionNo();
        lastProfileId=input.profileId(); lastProfileVersion=input.profileVersion(); lastRuleVersion=input.ruleVersion();
        lastNormalizationVersion=input.normalizationVersion(); observedOn=input.evaluatedOn(); updatedAt=now;
        return notice;
    }
}
