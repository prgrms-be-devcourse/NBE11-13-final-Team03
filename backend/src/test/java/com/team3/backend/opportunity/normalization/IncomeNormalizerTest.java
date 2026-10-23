package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.normalization.dto.YouthPolicyRaw;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IncomeNormalizerTest {

    private final IncomeNormalizer normalizer = new IncomeNormalizer(
            new CodeConditionNormalizer(CodeConditionNormalizerTest.table(Map.of())));

    @Test
    void 월_소득_금액_상한() {
        IncomeNormalization r = normalize(null, "월 소득 250만 원 이하");

        assertThat(r.status()).isEqualTo(IncomeStatus.MAX_ONLY);
        assertThat(r.type()).isEqualTo(IncomeType.AMOUNT);
        assertThat(r.max()).isEqualByComparingTo("2500000");
        assertThat(r.maxInclusive()).isTrue();
        assertThat(r.period()).isEqualTo("MONTHLY");
        assertThat(r.subject()).isEqualTo("UNKNOWN");
        assertThat(r.validationStatus()).isEqualTo(ValidationStatus.VALID);
    }

    @Test
    void 미만은_경계_미포함으로_보존한다() {
        IncomeNormalization r = normalize(null, "연 소득 5,000만 원 미만");

        assertThat(r.max()).isEqualByComparingTo("50000000");
        assertThat(r.maxInclusive()).isFalse();
        assertThat(r.period()).isEqualTo("YEARLY");
    }

    @Test
    void 초과와_이하가_함께_있으면_범위() {
        IncomeNormalization r = normalize(null, "월 소득 50만 원 초과 250만 원 이하");

        assertThat(r.status()).isEqualTo(IncomeStatus.RANGE);
        assertThat(r.min()).isEqualByComparingTo("500000");
        assertThat(r.minInclusive()).isFalse();
        assertThat(r.max()).isEqualByComparingTo("2500000");
    }

    @Test
    void 기준중위소득_비율과_가구_기준() {
        IncomeNormalization r = normalize(null, "가구 기준중위소득 120% 이하");

        assertThat(r.type()).isEqualTo(IncomeType.MEDIAN_PERCENT);
        assertThat(r.max()).isEqualByComparingTo("120");
        assertThat(r.subject()).isEqualTo("HOUSEHOLD");
    }

    @Test
    void 학자금_지원구간() {
        IncomeNormalization r = normalize(null, "학자금 지원구간 1~4구간");

        assertThat(r.type()).isEqualTo(IncomeType.STUDENT_LOAN_BRACKET);
        assertThat(r.min()).isEqualByComparingTo("1");
        assertThat(r.max()).isEqualByComparingTo("4");
    }

    @Test
    void 지원금과_재산은_소득_조건으로_읽지_않는다() {
        assertThat(normalize(null, "최대 500만 원 지원").status()).isEqualTo(IncomeStatus.UNKNOWN);
        assertThat(normalize(null, "소득 및 재산 3억 원 이하").status()).isEqualTo(IncomeStatus.UNKNOWN);
    }

    @Test
    void 서로_다른_기준이_여러_개면_복합으로_검토() {
        IncomeNormalization r = normalize(null, "1인 가구 월 소득 250만 원 이하\n2인 가구 월 소득 400만 원 이하");

        assertThat(r.status()).isEqualTo(IncomeStatus.COMPOSITE);
        assertThat(r.validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
    }

    @Test
    void 구조화_금액은_단위_미확인이라_검토() {
        YouthPolicyRaw raw = YouthPolicyRaw.builder().plcyNo("P").plcyNm("정책")
                .earnCndSeCd("E1").earnMinAmt("0").earnMaxAmt("5000").build();

        IncomeNormalization r = normalizer.normalize(raw);

        assertThat(r.status()).isEqualTo(IncomeStatus.MAX_ONLY);
        assertThat(r.unitConfirmed()).isFalse();
        assertThat(r.period()).isEqualTo("YEARLY");
        assertThat(r.validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
    }

    @Test
    void 제한없음_코드와_본문_기준이_다르면_모순() {
        IncomeNormalization noLimit = normalize("E0", null);
        assertThat(noLimit.status()).isEqualTo(IncomeStatus.NO_LIMIT);

        IncomeNormalization conflict = normalize("E0", "월 소득 250만 원 이하");
        assertThat(conflict.validationStatus()).isEqualTo(ValidationStatus.DATA_CONFLICT);
    }

    @Test
    void 아무_정보가_없으면_미확인() {
        IncomeNormalization r = normalize(null, null);

        assertThat(r.status()).isEqualTo(IncomeStatus.UNKNOWN);
        assertThat(r.validationStatus()).isEqualTo(ValidationStatus.UNKNOWN);
    }

    private IncomeNormalization normalize(String code, String earnEtcCn) {
        return normalizer.normalize(YouthPolicyRaw.builder().plcyNo("P").plcyNm("정책")
                .earnCndSeCd(code).earnEtcCn(earnEtcCn).build());
    }
}
