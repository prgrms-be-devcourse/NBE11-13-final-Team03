package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.service.EligibilityResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

/** Bounded observation page, reused by explicit calls and the opted-in batch refresh listener. */
@Service @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="yon.tracking.match.enabled",havingValue="true")
public class MatchCandidatePage {
    private final JdbcTemplate jdbc;
    private final MatchObservationTransaction observations;
    private final Clock clock;
    public record Run(long lastOpportunityId,boolean hasNext,int visited,int created,int failed) { }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Run scanUser(long userId,long afterOpportunityId,int size) {
        if (userId<1 || afterOpportunityId<0 || size<1 || size>500) throw new IllegalArgumentException("Invalid match scan page");
        var today=LocalDate.ofInstant(clock.instant(),KST);
        // Include NOT_ELIGIBLE and UNKNOWN too: skipping them would hide a later actual transition.
        var ids=jdbc.queryForList("""
                SELECT o.id FROM opportunities o JOIN opportunity_versions v ON v.id=o.current_version_id
                JOIN eligibility_profiles p ON p.user_id=?
                JOIN eligibility_set_results r ON r.user_id=p.user_id AND r.condition_set_id=v.condition_set_id
                WHERE o.id>? AND o.opportunity_type='YOUTH_POLICY' AND v.personalization_status='PERSONALIZABLE'
                  AND r.profile_version=p.profile_version AND r.rule_version=? AND (r.valid_until IS NULL OR r.valid_until>?)
                ORDER BY o.id LIMIT ?
                """,Long.class,userId,afterOpportunityId,EligibilityResultService.RULE_VERSION,today,size+1);
        int created=0,failed=0; long last=afterOpportunityId;
        for (long id:ids.stream().limit(size).toList()) {
            try {
                if (observations.observe(userId,id).status()==MatchObservationTransaction.Status.CANDIDATE_CREATED) created++;
            } catch (RuntimeException failure) {
                failed++; log.warn("Match observation failed: userId={}, opportunityId={}, error={}",userId,id,failure.getClass().getSimpleName());
            }
            last=id;
        }
        return new Run(last,ids.size()>size,Math.min(size,ids.size()),created,failed);
    }
}
