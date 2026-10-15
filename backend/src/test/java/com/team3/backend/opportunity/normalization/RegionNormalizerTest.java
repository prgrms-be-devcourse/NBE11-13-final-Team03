package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.region.directory.RegionIndex.Entry;
import com.team3.backend.region.directory.RegionIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegionNormalizerTest {

    /** 서울(종로·강남), 경기(수원시(장안구·권선구)·성남시), 세종 */
    private final RegionIndex index = new RegionIndex(List.of(
            new Entry("1100000000", "서울특별시", null),
            new Entry("1111000000", "종로구", "1100000000"),
            new Entry("1168000000", "강남구", "1100000000"),
            new Entry("4100000000", "경기도", null),
            new Entry("4111000000", "수원시", "4100000000"),
            new Entry("4111100000", "장안구", "4111000000"),
            new Entry("4111300000", "권선구", "4111000000"),
            new Entry("4113000000", "성남시", "4100000000"),
            new Entry("3611000000", "세종특별자치시", null)
    ));

    private final RegionNormalizer normalizer = new RegionNormalizer();

    @Test
    void 시군구_코드를_10자리로_매핑한다() {
        RegionNormalization result = normalizer.normalize("11680", index);

        assertThat(result.status()).isEqualTo(RegionStatus.SPECIFIC);
        assertThat(result.regionCodes()).containsExactly("1168000000");
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.VALID);
        assertThat(result.rawZipCd()).isEqualTo("11680");
    }

    @Test
    void 하위가_모두_있으면_상위로_묶는다() {
        assertThat(normalizer.normalize("11110,11680", index).regionCodes()).containsExactly("1100000000");
        // 수원시의 구가 모두 있으면 수원시, 수원시와 성남시가 모두 있으면 경기도
        assertThat(normalizer.normalize("41111,41113", index).regionCodes()).containsExactly("4111000000");
        assertThat(normalizer.normalize("41111, 41113, 41130", index).regionCodes()).containsExactly("4100000000");
    }

    @Test
    void 시와_그_하위_구가_함께_있으면_시만_남긴다() {
        assertThat(normalizer.normalize("41110,41111", index).regionCodes()).containsExactly("4111000000");
    }

    @Test
    void 모든_시도가_포함되면_전국() {
        RegionNormalization result = normalizer.normalize("11110,11680,41110,41130,36110", index);

        assertThat(result.status()).isEqualTo(RegionStatus.NATIONAL);
        assertThat(result.regionCodes()).isEmpty();
    }

    @Test
    void 빈_값은_전국이_아니라_미확인() {
        RegionNormalization result = normalizer.normalize("", index);

        assertThat(result.status()).isEqualTo(RegionStatus.UNKNOWN);
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.UNKNOWN);
    }

    @Test
    void 사전에_없는_코드는_버리지_않고_기록하고_검토로_남긴다() {
        RegionNormalization partial = normalizer.normalize("11680,29110", index);
        assertThat(partial.regionCodes()).containsExactly("1168000000");
        assertThat(partial.unmappedCodes()).containsExactly("29110");
        assertThat(partial.validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);

        RegionNormalization none = normalizer.normalize("29110,46110", index);
        assertThat(none.status()).isEqualTo(RegionStatus.UNKNOWN);
        assertThat(none.unmappedCodes()).containsExactly("29110", "46110");
    }
}
