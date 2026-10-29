package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.tracking.domain.model.EligibilityTransitionDecision;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static com.team3.backend.eligibility.domain.entity.EligibilityStatus.*;
import static com.team3.backend.tracking.domain.model.EligibilityTransitionDecision.*;

/**
 * Development-only policy selection, pending team agreement. The opted-in eligibility consumer preserves deferred results.
 * Same-profile/same-rule/same-date comparison and latest-policy revalidation are caller responsibilities.
 */
@Component
public class ProvisionalEligibilityTransitionClassifier {
    public static final String POLICY_VERSION = "development-provisional-v1";

    public EligibilityTransitionDecision classify(EligibilityStatus before, EligibilityStatus after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before == after) return NO_CHANGE;
        if (before == NOT_ELIGIBLE && after == ELIGIBLE) return BECAME_ELIGIBLE;
        if (before == ELIGIBLE && after == NOT_ELIGIBLE) return ELIGIBILITY_DOWNGRADED;
        if (before == ELIGIBLE && after == POSSIBLY_ELIGIBLE) return ELIGIBILITY_REVIEW_REQUIRED;
        // UNKNOWN is not equivalent to lost eligibility. Other changed pairs remain explicitly unresolved.
        return DEFERRED;
    }
}
