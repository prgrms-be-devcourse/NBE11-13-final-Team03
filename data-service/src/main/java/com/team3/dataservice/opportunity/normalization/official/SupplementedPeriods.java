package com.team3.dataservice.opportunity.normalization.official;

import com.team3.dataservice.opportunity.domain.status.ApplicationPeriod;

import java.util.List;

public record SupplementedPeriods(List<ApplicationPeriod> periods, Evidence evidence, SupplementOutcome outcome, String note) {
}
