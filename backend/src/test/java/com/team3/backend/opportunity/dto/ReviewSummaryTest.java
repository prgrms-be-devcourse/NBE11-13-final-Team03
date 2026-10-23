package com.team3.backend.opportunity.dto;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.normalization.dto.NormalizationResultResponse.ReviewSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSummaryTest {

    @Test
    void VALID를_뺀_검증_상태를_모두_집계한다() {
        ReviewSummary summary = ReviewSummary.of(List.of(
                ValidationStatus.VALID,
                ValidationStatus.NEEDS_REVIEW,
                ValidationStatus.DATA_CONFLICT, ValidationStatus.DATA_CONFLICT,
                ValidationStatus.INVALID,
                ValidationStatus.UNKNOWN,
                ValidationStatus.UNVERIFIED, ValidationStatus.UNVERIFIED, ValidationStatus.UNVERIFIED));

        assertThat(summary).isEqualTo(new ReviewSummary(1, 2, 1, 1, 3));
    }

    @Test
    void 새_검증_상태가_추가되면_집계에서_빠지지_않게_한다() {
        long counted = ReviewSummary.class.getRecordComponents().length;

        assertThat(counted).isEqualTo(ValidationStatus.values().length - 1);
    }
}
