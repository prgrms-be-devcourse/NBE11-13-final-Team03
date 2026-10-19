package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeConditionNormalizerTest {

    /** 테스트용 가짜 코드표 (실제 코드값 아님) */
    private final CodeConditionNormalizer normalizer = new CodeConditionNormalizer(table(Map.of(
            "A1", "UNEMPLOYED", "A2", "EMPLOYED", "A9", "OTHER", "A0", "NO_LIMIT")));

    @Test
    void 여러_코드를_나눠_매핑한다() {
        CodeNormalization result = normalizer.normalize("jobCd", "A1,A2");

        assertThat(result.status()).isEqualTo(CodeConditionStatus.SPECIFIC);
        assertThat(result.values()).containsExactly("EMPLOYED", "UNEMPLOYED");
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.VALID);
    }

    @Test
    void 제한없음_코드() {
        CodeNormalization result = normalizer.normalize("jobCd", "A0");

        assertThat(result.status()).isEqualTo(CodeConditionStatus.NO_LIMIT);
        assertThat(result.values()).isEmpty();
    }

    @Test
    void 빈_값은_제한없음이_아니라_미확인() {
        CodeNormalization result = normalizer.normalize("jobCd", " ");

        assertThat(result.status()).isEqualTo(CodeConditionStatus.UNKNOWN);
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.UNKNOWN);
    }

    @Test
    void 코드표에_없는_코드나_기타_제한없음과_값이_섞이면_검토() {
        assertThat(normalizer.normalize("jobCd", "A1,Z9").validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
        assertThat(normalizer.normalize("jobCd", "A1,Z9").unmappedCodes()).containsExactly("Z9");
        assertThat(normalizer.normalize("jobCd", "A9").validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
        assertThat(normalizer.normalize("jobCd", "A0,A1").validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
    }

    @Test
    void 코드표가_비어_있으면_추측하지_않고_검토로_남긴다() {
        CodeNormalization result = new CodeConditionNormalizer(new YouthPolicyCodeTable()).normalize("mrgSttsCd", "0055001");

        assertThat(result.status()).isEqualTo(CodeConditionStatus.UNKNOWN);
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
        assertThat(result.unmappedCodes()).containsExactly("0055001");
    }

    static YouthPolicyCodeTable table(Map<String, String> jobCodes) {
        YouthPolicyCodeTable table = new YouthPolicyCodeTable();
        table.getFields().put("jobCd", jobCodes);
        table.getFields().put("earnCndSeCd", Map.of("E0", "NO_LIMIT", "E1", "ANNUAL_INCOME", "E9", "OTHER"));
        return table;
    }
}
