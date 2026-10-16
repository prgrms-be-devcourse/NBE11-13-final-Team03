package com.team3.backend.opportunity.normalization.official;

import com.team3.backend.opportunity.normalization.ApplicationPeriod;

import java.util.List;

public record SupplementedPeriods(List<ApplicationPeriod> periods, Evidence evidence, SupplementOutcome outcome, String note) {
}
