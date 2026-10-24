package com.team3.backend.tracking.domain.model;

import com.team3.backend.eligibility.service.EligibilityEvaluator.Judgement;
import java.time.LocalDate;

/** Comparison evidence only; a status difference is not itself permission to send an alert. */
public record EligibilityVersionComparison(
        Long userId, Long fromVersionId, Long toVersionId,
        Long profileVersion, String ruleVersion, LocalDate evaluatedOn,
        Judgement before, Judgement after
) {
}
