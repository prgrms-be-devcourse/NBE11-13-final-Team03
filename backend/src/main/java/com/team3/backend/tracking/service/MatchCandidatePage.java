package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.opportunity.dto.ApplicationWindowRow;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import com.team3.backend.tracking.domain.model.MatchObservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

/**
 * Bounded observation page, reused by explicit calls and the opted-in batch refresh listener.
 * The page reads every current input without locks, builds the same input key the pair stores, and only hands
 * policies whose key differs (or that have no pair yet) to the locked observation transaction. A lock-free read
 * can race a concurrent change; that only means the change is observed under the lock now or on the next refresh,
 * never that a stale key is persisted, because the observation transaction recomputes everything under its locks.
 */
@Service @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="yon.tracking.match.enabled",havingValue="true")
public class MatchCandidatePage {
    private final JdbcTemplate jdbc;
    private final ApplicationWindowRepository windows;
    private final ApplicationStatusCalculator applicationStatus;
    private final MatchTrackingProperties settings;
    private final MatchObservationTransaction observations;
    private final Clock clock;
    public record Run(long lastOpportunityId,boolean hasNext,int visited,int unchanged,int created,int failed) { }
    private record Row(long opportunityId,long versionId,int versionNo,String normalizationVersion,long profileId,long profileVersion,
            String ruleVersion,EligibilityStatus status,int missing,int check,boolean saved,boolean interest,String lastInputKey) { }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Run scanUser(long userId,long afterOpportunityId,int size) {
        if (userId<1 || afterOpportunityId<0 || size<1 || size>500) throw new IllegalArgumentException("Invalid match scan page");
        var today=LocalDate.ofInstant(clock.instant(),KST);
        // Include NOT_ELIGIBLE and UNKNOWN too: skipping them would hide a later actual transition.
        // Same eligibility filters as the locked observation; the pair join only supplies the last stored key.
        var rows=jdbc.query("""
                SELECT o.id,v.id,v.version_no,v.normalization_version,p.id,p.profile_version,
                       r.rule_version,r.eligibility_status,r.user_info_missing_count,r.check_required_count,
                       EXISTS(SELECT 1 FROM user_opportunity_preferences s
                              WHERE s.user_id=p.user_id AND s.opportunity_id=o.id AND s.is_saved) AS saved,
                       EXISTS(SELECT 1 FROM user_interests i WHERE i.user_id=p.user_id AND
                              ((i.interest_type='CATEGORY' AND i.interest_code=o.category)
                               OR (i.interest_type='SUB_CATEGORY' AND i.interest_code=o.sub_category))) AS interest,
                       m.last_input_key
                FROM opportunities o JOIN opportunity_versions v ON v.id=o.current_version_id
                JOIN eligibility_profiles p ON p.user_id=?
                JOIN eligibility_set_results r ON r.user_id=p.user_id AND r.condition_set_id=v.condition_set_id
                LEFT JOIN notification_match_pairs m ON m.user_id=p.user_id AND m.opportunity_id=o.id
                WHERE o.id>? AND o.opportunity_type='YOUTH_POLICY' AND v.personalization_status='PERSONALIZABLE'
                  AND r.profile_version=p.profile_version AND r.rule_version=? AND (r.valid_until IS NULL OR r.valid_until>?)
                ORDER BY o.id LIMIT ?
                """,(rs,row)->new Row(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getString(4),rs.getLong(5),rs.getLong(6),
                        rs.getString(7),EligibilityStatus.valueOf(rs.getString(8)),rs.getInt(9),rs.getInt(10),
                        rs.getBoolean(11),rs.getBoolean(12),rs.getString(13)),
                userId,afterOpportunityId,EligibilityResultService.RULE_VERSION,today,size+1);
        var page=rows.stream().limit(size).toList();
        var periods=page.isEmpty() ? Map.<Long,List<ApplicationPeriod>>of()
                : windows.findRowsByVersionIdIn(page.stream().map(Row::versionId).distinct().toList()).stream()
                    .collect(Collectors.groupingBy(ApplicationWindowRow::versionId,Collectors.mapping(w->new ApplicationPeriod(
                            w.periodType(),w.startDate(),w.endDate(),w.validationStatus(),w.periodText()),Collectors.toList())));
        int unchanged=0,created=0,failed=0; long last=afterOpportunityId;
        for (var row:page) {
            try {
                var input=new MatchObservation(row.versionId(),row.versionNo(),row.profileId(),row.profileVersion(),row.ruleVersion(),
                        row.normalizationVersion(),row.status(),OpportunityType.YOUTH_POLICY,PersonalizationStatus.PERSONALIZABLE,
                        applicationStatus.calculate(periods.getOrDefault(row.versionId(),List.of()),today),row.saved(),row.interest(),
                        row.missing(),row.check(),today);
                if (input.inputKey(settings.possibly()).equals(row.lastInputKey())) unchanged++;
                else if (observations.observe(userId,row.opportunityId()).status()==MatchObservationTransaction.Status.CANDIDATE_CREATED) created++;
            } catch (RuntimeException failure) {
                failed++; log.warn("Match observation failed: userId={}, opportunityId={}, error={}",userId,row.opportunityId(),failure.getClass().getSimpleName());
            }
            last=row.opportunityId();
        }
        return new Run(last,rows.size()>size,page.size(),unchanged,created,failed);
    }
}
