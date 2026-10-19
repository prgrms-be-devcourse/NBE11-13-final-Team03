package com.team3.backend.opportunity.normalization.official;

import com.team3.backend.opportunity.normalization.AgeNormalization;

public record SupplementedAge(AgeNormalization result, Evidence evidence, SupplementOutcome outcome, String note) {
}
