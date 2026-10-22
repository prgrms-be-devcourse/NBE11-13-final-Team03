package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.dto.YouthPolicyRaw;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgeNormalizerTest {

    private final AgeNormalizer normalizer = new AgeNormalizer();

    @Test
    void 최소_최대_연령이_있으면_RANGE() {
        AgeNormalization result = normalize("19", "34", "Y");

        assertThat(result.status()).isEqualTo(AgeStatus.RANGE);
        assertThat(result.min()).isEqualTo(19);
        assertThat(result.max()).isEqualTo(34);
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.VALID);
    }

    @Test
    void 영영Y는_제한없음이_아니라_모순으로_남긴다() {
        AgeNormalization result = normalize("0", "0", "Y");

        assertThat(result.status()).isEqualTo(AgeStatus.UNKNOWN);
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.DATA_CONFLICT);
        assertThat(result.dataConflict()).isTrue();
        assertThat(result.raw()).containsEntry("sprtTrgtMinAge", "0").containsEntry("sprtTrgtAgeLmtYn", "Y");
    }

    @Test
    void 영은_실제_나이가_아니라_미입력으로_본다() {
        assertThat(normalize("19", "0", "Y").status()).isEqualTo(AgeStatus.MIN_ONLY);
        assertThat(normalize("0", "39", "Y").status()).isEqualTo(AgeStatus.MAX_ONLY);
        assertThat(normalize("", "39", "Y").max()).isEqualTo(39);
    }

    @Test
    void 제한없음_N이고_연령도_없으면_공식_의미_확인_전까지_확정하지_않는다() {
        AgeNormalization result = normalize("0", "0", "N");

        assertThat(result.status()).isEqualTo(AgeStatus.UNKNOWN);
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
    }

    @Test
    void 제한없음_N이어도_연령_값이_있으면_값을_쓴다() {
        AgeNormalization range = normalize("19", "34", "N");
        assertThat(range.status()).isEqualTo(AgeStatus.RANGE);
        assertThat(range.min()).isEqualTo(19);
        assertThat(range.max()).isEqualTo(34);
        assertThat(range.validationStatus()).isEqualTo(ValidationStatus.VALID);
        assertThat(range.dataConflict()).isFalse();
        assertThat(range.reason()).contains("N");
        assertThat(range.raw()).containsEntry("sprtTrgtAgeLmtYn", "N");

        assertThat(normalize("18", "0", "N").status()).isEqualTo(AgeStatus.MIN_ONLY);
        assertThat(normalize("0", "39", "N").max()).isEqualTo(39);
    }

    @Test
    void 제한없음_N이어도_연령_값이_잘못되면_오류() {
        assertThat(normalize("40", "34", "N").validationStatus()).isEqualTo(ValidationStatus.INVALID);
    }

    @Test
    void 최소가_최대보다_크거나_숫자가_아니면_오류() {
        assertThat(normalize("40", "34", "Y").validationStatus()).isEqualTo(ValidationStatus.INVALID);
        assertThat(normalize("만19세", "34", "Y").validationStatus()).isEqualTo(ValidationStatus.INVALID);
        assertThat(normalize("19", "999", "Y").validationStatus()).isEqualTo(ValidationStatus.INVALID);
    }

    @Test
    void 아무_정보가_없으면_미확인() {
        AgeNormalization result = normalize("", "", "");

        assertThat(result.status()).isEqualTo(AgeStatus.UNKNOWN);
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.UNKNOWN);
        assertThat(result.dataConflict()).isFalse();
    }

    private AgeNormalization normalize(String min, String max, String limitYn) {
        return normalizer.normalize(new YouthPolicyRaw("P1", "정책", min, max, limitYn, null, null, null, null));
    }
}
