package com.team3.dataservice.opportunity.normalization.official;

import com.team3.dataservice.opportunity.normalization.AgeNormalization;

public record SupplementedAge(AgeNormalization result, Evidence evidence, SupplementOutcome outcome, String note) {
}
