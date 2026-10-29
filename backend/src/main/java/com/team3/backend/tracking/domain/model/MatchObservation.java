package com.team3.backend.tracking.domain.model;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.tracking.config.MatchTrackingProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/** Current inputs only. A previous status comes from the match pair, never an overwritten result cache. */
public record MatchObservation(long versionId, int versionNo, long profileId, long profileVersion,
        String ruleVersion, String normalizationVersion, EligibilityStatus status, OpportunityType type,
        PersonalizationStatus personalization, ApplicationStatus applicationStatus, boolean saved,
        boolean interestMatched, int userInfoMissing, int checkRequired, LocalDate evaluatedOn) {
    public enum Qualification { NONE, ELIGIBLE, POSSIBLY }
    public MatchObservation {
        if (versionId<1 || versionNo<1 || profileId<1 || profileVersion<1 || userInfoMissing<0 || checkRequired<0
                || ruleVersion==null || ruleVersion.isBlank() || normalizationVersion==null || normalizationVersion.isBlank())
            throw new IllegalArgumentException("Persisted current inputs and non-negative counts required");
        Objects.requireNonNull(status); Objects.requireNonNull(type); Objects.requireNonNull(personalization);
        Objects.requireNonNull(applicationStatus); Objects.requireNonNull(evaluatedOn);
    }
    public Qualification qualify(MatchTrackingProperties.Possibly policy) {
        if (type!=OpportunityType.YOUTH_POLICY || personalization!=PersonalizationStatus.PERSONALIZABLE
                || !(applicationStatus==ApplicationStatus.OPEN || applicationStatus==ApplicationStatus.ALWAYS_OPEN
                    || (saved && applicationStatus==ApplicationStatus.UPCOMING))) return Qualification.NONE;
        if (status==EligibilityStatus.ELIGIBLE) return Qualification.ELIGIBLE;
        if (status==EligibilityStatus.POSSIBLY_ELIGIBLE
                && (long)userInfoMissing+checkRequired<=policy.maxUnresolved()
                && (!policy.requireInterest() || interestMatched)) return Qualification.POSSIBLY;
        return Qualification.NONE;
    }
    /** Date alone is not a new occurrence. Status/period/inputs or policy changes are observable changes. */
    public String inputKey(MatchTrackingProperties.Possibly policy) {
        String value=String.join("|",Long.toString(versionId),Integer.toString(versionNo),Long.toString(profileId),
                Long.toString(profileVersion),ruleVersion,normalizationVersion,status.name(),type.name(),personalization.name(),
                applicationStatus.name(),Boolean.toString(saved),Boolean.toString(interestMatched),
                Integer.toString(userInfoMissing),Integer.toString(checkRequired),Integer.toString(policy.maxUnresolved()),
                Boolean.toString(policy.requireInterest()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
