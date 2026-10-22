package com.team3.backend.opportunity.normalization.official;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.dto.YouthPolicyRaw;
import com.team3.backend.opportunity.normalization.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialSupplementerTest {

    private final OfficialSupplementer supplementer =
            new OfficialSupplementer(new AgeEvidenceExtractor(), new PeriodEvidenceExtractor());
    private final AgeNormalizer ageNormalizer = new AgeNormalizer();
    private final ApplicationPeriodNormalizer periodNormalizer = new ApplicationPeriodNormalizer();

    // ── 연령 ──

    @Test
    void 영영Y를_공식_공고의_연령으로_채우고_원본_모순은_기록한다() {
        YouthPolicyRaw raw = raw("0", "0", "Y", "0057001", "20261001 ~ 20261031");
        String html = """
                <html><body><h1>2026년 청년 월세 지원</h1>
                <table><tr><th>지원대상</th><td>만 19세 이상 34세 이하 무주택 청년</td></tr></table>
                </body></html>""";

        SupplementedAge result = supplementer.supplementAge(raw, ageNormalizer.normalize(raw), doc(SourceType.OFFICIAL_NOTICE, html));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.FILLED);
        assertThat(result.result().status()).isEqualTo(AgeStatus.RANGE);
        assertThat(result.result().min()).isEqualTo(19);
        assertThat(result.result().max()).isEqualTo(34);
        assertThat(result.result().validationStatus()).isEqualTo(ValidationStatus.VALID);
        assertThat(result.result().dataConflict()).isTrue();
        assertThat(result.result().raw()).containsEntry("sprtTrgtMinAge", "0");
        assertThat(result.evidence().evidenceText()).contains("만 19세 이상 34세 이하");
        assertThat(result.evidence().confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void 정책명이_원문에_없으면_반영하지_않는다() {
        YouthPolicyRaw raw = raw("0", "0", "Y", null, null);

        SupplementedAge result = supplementer.supplementAge(raw, ageNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_NOTICE, "청년 창업 지원 안내\n지원대상: 만 19세 이상 39세 이하"));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.DOCUMENT_MISMATCH);
        assertThat(result.result().status()).isEqualTo(AgeStatus.UNKNOWN);
    }

    @Test
    void 이_정책의_연도와_다른_연도_공고는_반영하지_않는다() {
        YouthPolicyRaw raw = raw("0", "0", "Y", "0057001", "20261001 ~ 20261031");

        SupplementedAge result = supplementer.supplementAge(raw, ageNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_NOTICE, "2025년 청년 월세 지원 공고\n지원대상: 만 19세 이상 34세 이하"));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.DOCUMENT_MISMATCH);
    }

    // ── API 자유텍스트 ──

    @Test
    void 외부_원문_전에_API_추가자격_텍스트로_먼저_보완한다() {
        YouthPolicyRaw raw = new YouthPolicyRaw("P1", "청년 월세 지원", "0", "0", "Y", null,
                "공고일 기준 만 19세 이상 34세 이하 무주택 청년", null, null, null, null, null);

        SupplementedAge result = supplementer.supplementAgeFromApiText(raw, ageNormalizer.normalize(raw));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.FILLED);
        assertThat(result.result().min()).isEqualTo(19);
        assertThat(result.evidence().sourceType()).isEqualTo(SourceType.API_TEXT);
        assertThat(result.evidence().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(result.evidence().evidenceText()).startsWith("[addAplyQlfcCndCn]");
        assertThat(result.result().validationStatus()).isEqualTo(ValidationStatus.UNVERIFIED);
    }

    @Test
    void API_텍스트로_채운_값은_공식_공고로_다시_채우면_VALID가_된다() {
        YouthPolicyRaw raw = new YouthPolicyRaw("P1", "청년 월세 지원", "0", "0", "Y", null,
                "공고일 기준 만 19세 이상 34세 이하 무주택 청년", null, null, null, null, null);
        AgeNormalization fromText = supplementer.supplementAgeFromApiText(raw, ageNormalizer.normalize(raw)).result();

        SupplementedAge official = supplementer.supplementAge(raw, fromText,
                doc(SourceType.OFFICIAL_NOTICE, "청년 월세 지원\n지원대상: 만 19세 이상 39세 이하"));

        assertThat(official.outcome()).isEqualTo(SupplementOutcome.FILLED);
        assertThat(official.result().max()).isEqualTo(39);
        assertThat(official.result().validationStatus()).isEqualTo(ValidationStatus.VALID);
    }

    @Test
    void API_텍스트로_채운_값은_공식이_아닌_출처로_VALID나_NEEDS_REVIEW가_되지_않는다() {
        YouthPolicyRaw raw = new YouthPolicyRaw("P1", "청년 월세 지원", "0", "0", "Y", null,
                "공고일 기준 만 19세 이상 34세 이하 무주택 청년", null, null, null, null, null);
        AgeNormalization fromText = supplementer.supplementAgeFromApiText(raw, ageNormalizer.normalize(raw)).result();

        SupplementedAge detail = supplementer.supplementAge(raw, fromText,
                doc(SourceType.YOUTH_CENTER_DETAIL, "청년 월세 지원\n지원대상: 만 19세 이상 34세 이하"));
        SupplementedAge split = supplementer.supplementAge(raw, fromText,
                doc(SourceType.OFFICIAL_NOTICE, "청년 월세 지원\n지원대상: 대학생 만 19~34세, 창업자 만 19~39세"));

        assertThat(detail.result().validationStatus()).isEqualTo(ValidationStatus.UNVERIFIED);
        assertThat(split.outcome()).isEqualTo(SupplementOutcome.NEEDS_REVIEW);
        assertThat(split.result().validationStatus()).isEqualTo(ValidationStatus.UNVERIFIED);
        assertThat(split.result().max()).isEqualTo(34);
    }

    @Test
    void 의미가_확정되지_않은_ptcpPrpTrgtCn은_연령_근거로_쓰지_않는다() {
        YouthPolicyRaw raw = new YouthPolicyRaw("P1", "청년 월세 지원", "0", "0", "Y",
                "만 40세 이상 참여 제한", null, null, null, null, null, null);

        SupplementedAge result = supplementer.supplementAgeFromApiText(raw, ageNormalizer.normalize(raw));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.NOT_APPLIED);
        assertThat(result.result().status()).isEqualTo(AgeStatus.UNKNOWN);
    }

    @Test
    void 연령_문맥이_없는_숫자는_연령으로_보지_않는다() {
        YouthPolicyRaw raw = raw("0", "0", "Y", null, null);

        SupplementedAge result = supplementer.supplementAge(raw, ageNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_NOTICE, "청년 월세 지원\n복지관 이용 안내: 65세 이상 어르신 무료"));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.NOT_APPLIED);
    }

    @Test
    void 대상별로_연령이_다르면_고르지_않고_검토로_남긴다() {
        YouthPolicyRaw raw = raw("0", "0", "Y", null, null);

        SupplementedAge result = supplementer.supplementAge(raw, ageNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_NOTICE, "청년 월세 지원\n지원대상: 대학생 만 19~34세, 창업자 만 19~39세"));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.NEEDS_REVIEW);
        assertThat(result.result().status()).isEqualTo(AgeStatus.UNKNOWN);
        assertThat(result.result().validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
    }

    @Test
    void API와_공식_공고가_다르면_공식_우선_그외_출처는_검토() {
        YouthPolicyRaw raw = raw("19", "34", "Y", null, null);
        String text = "청년 월세 지원\n신청자격: 만 19세 이상 39세 이하";

        SupplementedAge official = supplementer.supplementAge(raw, ageNormalizer.normalize(raw), doc(SourceType.OFFICIAL_NOTICE, text));
        assertThat(official.outcome()).isEqualTo(SupplementOutcome.OVERRIDDEN);
        assertThat(official.result().max()).isEqualTo(39);
        assertThat(official.result().dataConflict()).isTrue();

        SupplementedAge detail = supplementer.supplementAge(raw, ageNormalizer.normalize(raw), doc(SourceType.YOUTH_CENTER_DETAIL, text));
        assertThat(detail.outcome()).isEqualTo(SupplementOutcome.NEEDS_REVIEW);
        assertThat(detail.result().max()).isEqualTo(34);
    }

    @Test
    void 같은_값이면_확인으로_처리한다() {
        YouthPolicyRaw raw = raw("19", "34", "Y", null, null);

        SupplementedAge result = supplementer.supplementAge(raw, ageNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_PAGE, "청년 월세 지원\n지원대상 만 19~34세 청년"));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.CONFIRMED);
        assertThat(result.evidence().sourceType()).isEqualTo(SourceType.OFFICIAL_PAGE);
    }

    @Test
    void 제한없음_미만_출생일_표현을_읽는다() {
        YouthPolicyRaw raw = raw("0", "0", "Y", null, null);

        assertThat(age(raw, "청년 월세 지원\n지원대상: 연령 제한 없음").status()).isEqualTo(AgeStatus.NO_LIMIT);

        AgeNormalization under = age(raw, "청년 월세 지원\n신청자격: 만 39세 미만 청년");
        assertThat(under.status()).isEqualTo(AgeStatus.MAX_ONLY);
        assertThat(under.max()).isEqualTo(38);

        AgeNormalization born = age(raw, "청년 월세 지원\n지원대상: 1986. 1. 1. ~ 2007. 12. 31. 출생자");
        assertThat(born.status()).isEqualTo(AgeStatus.DATE_BOUND);
        assertThat(born.bornOnOrAfter()).isEqualTo(LocalDate.of(1986, 1, 1));
        assertThat(born.bornOnOrBefore()).isEqualTo(LocalDate.of(2007, 12, 31));
    }

    // ── 신청기간 ──

    @Test
    void 신청기간이_없으면_원문으로_채우고_사업기간은_쓰지_않는다() {
        YouthPolicyRaw raw = new YouthPolicyRaw("P1", "2026년 청년 월세 지원", "19", "34", "Y", null, null, "0057001", "");
        String text = """
                청년 월세 지원
                신청기간 : 2026. 10. 1. ~ 2026. 10. 31.
                사업기간 : 2026.11.01 ~ 2026.12.31""";

        SupplementedPeriods result = supplementer.supplementPeriods(raw, periodNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_NOTICE, text));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.FILLED);
        assertThat(result.periods()).hasSize(1);
        assertThat(result.periods().get(0).startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(result.periods().get(0).endDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void 연도_단서가_없으면_원문_신청기간을_자동으로_채우지_않는다() {
        YouthPolicyRaw raw = raw("19", "34", "Y", "0057001", "");

        SupplementedPeriods result = supplementer.supplementPeriods(raw, periodNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_NOTICE, "청년 월세 지원\n신청기간 : 2024.10.01 ~ 2024.10.31"));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.NEEDS_REVIEW);
        assertThat(result.periods().get(0).startDate()).isNull();
    }

    @Test
    void 상시_마감_코드는_원문으로_바꾸지_않는다() {
        YouthPolicyRaw raw = raw("19", "34", "Y", "0057002", "");

        SupplementedPeriods result = supplementer.supplementPeriods(raw, periodNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_NOTICE, "청년 월세 지원\n접수기간: 2026.10.01 ~ 2026.10.31"));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.NOT_APPLIED);
        assertThat(result.periods().get(0).periodType()).isEqualTo(PeriodType.ALWAYS);
    }

    @Test
    void 신청기간이_다르면_공식_원문_우선() {
        YouthPolicyRaw raw = raw("19", "34", "Y", "0057001", "20261001 ~ 20261031");

        SupplementedPeriods result = supplementer.supplementPeriods(raw, periodNormalizer.normalize(raw),
                doc(SourceType.OFFICIAL_NOTICE, "청년 월세 지원\n모집기간: 2026.10.01 ~ 2026.11.15 (연장)"));

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.OVERRIDDEN);
        assertThat(result.periods().get(0).endDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(result.evidence().dataConflict()).isTrue();
    }

    @Test
    void 원문이_없으면_API_결과를_그대로_쓴다() {
        YouthPolicyRaw raw = raw("19", "34", "Y", "0057001", "20261001 ~ 20261031");
        List<ApplicationPeriod> api = periodNormalizer.normalize(raw);

        SupplementedPeriods result = supplementer.supplementPeriods(raw, api, null);

        assertThat(result.outcome()).isEqualTo(SupplementOutcome.NOT_APPLIED);
        assertThat(result.periods()).isEqualTo(api);
    }

    private AgeNormalization age(YouthPolicyRaw raw, String text) {
        return supplementer.supplementAge(raw, ageNormalizer.normalize(raw), doc(SourceType.OFFICIAL_NOTICE, text)).result();
    }

    private static YouthPolicyRaw raw(String min, String max, String yn, String code, String ymd) {
        return new YouthPolicyRaw("P1", "청년 월세 지원", min, max, yn, null, null, code, ymd);
    }

    private static OfficialDocument doc(SourceType type, String content) {
        return new OfficialDocument(type, "https://example.go.kr/notice/1", content, "hash-1",
                OffsetDateTime.parse("2026-10-15T10:00:00+09:00"));
    }
}
