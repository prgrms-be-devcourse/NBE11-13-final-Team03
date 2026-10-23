package com.team3.backend.opportunity.normalization.official;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.normalization.dto.YouthPolicyRaw;
import com.team3.backend.opportunity.normalization.AgeNormalization;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API 규칙 기반 결과를 근거로 보완한다. 원본(API 값)은 바꾸지 않고 결과와 근거만 새로 만든다.
 *
 * 보완 순서 (호출하는 쪽에서 차례로 적용)
 * 1. API 구조화 필드 → AgeNormalizer / ApplicationPeriodNormalizer
 * 2. API 자유텍스트 → {@link #supplementAgeFromApiText} (외부 요청 없이 먼저 확인, 출처 API_TEXT)
 * 3. 공식 원문 → {@link #supplementAge}, {@link #supplementPeriods}
 *
 * 공통 원칙
 * - 원문에 정책명이 없거나, 이 정책의 연도 단서와 다른 연도 문서면 반영하지 않는다 (DOCUMENT_MISMATCH).
 * - 연도 단서(정책명·aplyYmd)가 없으면 신청기간은 자동으로 채우지 않는다 (이전 연도 공고 혼입 방지).
 * - 근거가 여러 개로 갈리면(대상별 연령, 예외) 고르지 않고 NEEDS_REVIEW.
 * - API 값이 없거나 모순이면 근거로 채운다 (FILLED). 공식 출처(HIGH)면 VALID, 그 외(API 자유텍스트 등)는 UNVERIFIED.
 *   UNVERIFIED 값은 이후 공식 출처 근거로 다시 채워질 때만 VALID가 된다.
 * - API와 근거가 다르면: 공식 출처(HIGH)는 근거 우선 + dataConflict, 그 외는 자동 반영하지 않음 (NEEDS_REVIEW).
 * - 신뢰도 LOW 출처는 자동 반영하지 않는다.
 * - 수집 쪽에서 여러 정책이 함께 있는 페이지는 해당 정책 구간만 잘라 넘기는 것을 전제로 한다.
 *   잘리지 않아 다른 정책 조건이 섞이면 근거가 여러 개가 되어 NEEDS_REVIEW로 걸러진다.
 */
@Component
@RequiredArgsConstructor
public class OfficialSupplementer {

    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");

    private final AgeEvidenceExtractor ageExtractor;
    private final PeriodEvidenceExtractor periodExtractor;

    /**
     * API 자유텍스트에서 연령 근거를 찾는다. 같은 정책의 데이터이므로 정책명·연도 확인은 하지 않는다.
     * ptcpPrpTrgtCn(참여제한대상내용)은 제외 대상이므로 자격 근거로 쓰지 않는다 (EXCLUSION 조건으로 따로 정규화).
     * plcySprtCn(지원 내용)은 자격이 아니라 혜택이므로 쓰지 않는다.
     */
    public SupplementedAge supplementAgeFromApiText(YouthPolicyRaw raw, AgeNormalization api) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("addAplyQlfcCndCn", raw.addAplyQlfcCndCn());
        fields.put("plcyExplnCn", raw.plcyExplnCn());
        fields.put("earnEtcCn", raw.earnEtcCn());
        fields.put("etcMttrCn", raw.etcMttrCn());

        List<String> lines = new ArrayList<>();
        fields.forEach((name, value) -> OfficialText.lines(value).forEach(line -> lines.add("[" + name + "] " + line)));

        OfficialDocument apiText = new OfficialDocument(SourceType.API_TEXT, null, String.join("\n", lines), null, null);
        return applyAge(api, lines, apiText);
    }

    public SupplementedAge supplementAge(YouthPolicyRaw raw, AgeNormalization api, OfficialDocument doc) {
        Match match = match(raw, doc);
        if (match.lines() == null) {
            return new SupplementedAge(api, apiEvidence(api.raw().toString(), api.dataConflict()), match.outcome(), match.reason());
        }
        return applyAge(api, match.lines(), doc);
    }

    public SupplementedPeriods supplementPeriods(YouthPolicyRaw raw, List<ApplicationPeriod> api, OfficialDocument doc) {
        Evidence apiEvidence = apiEvidence("aplyPrdSeCd=" + raw.aplyPrdSeCd() + ", aplyYmd=" + raw.aplyYmd(), false);
        boolean apiDeclaredByCode = api.stream()
                .anyMatch(p -> p.periodType() == PeriodType.ALWAYS || p.periodType() == PeriodType.CLOSED);
        if (apiDeclaredByCode) {
            return new SupplementedPeriods(api, apiEvidence, SupplementOutcome.NOT_APPLIED, "상시·마감 코드는 API 값을 따름");
        }
        Match match = match(raw, doc);
        if (match.lines() == null) {
            return new SupplementedPeriods(api, apiEvidence, match.outcome(), match.reason());
        }
        List<ApplicationPeriod> official = periodExtractor.extract(match.lines()).stream()
                .filter(p -> p.validationStatus() == ValidationStatus.VALID)
                .toList();
        if (official.isEmpty()) {
            return new SupplementedPeriods(api, apiEvidence, SupplementOutcome.NOT_APPLIED, "원문에서 신청기간 근거를 찾지 못함");
        }
        String evidenceText = official.stream().map(ApplicationPeriod::periodText).collect(Collectors.joining("\n"));
        if (expectedYears(raw).isEmpty()) {
            return new SupplementedPeriods(api, evidence(doc, evidenceText, false), SupplementOutcome.NEEDS_REVIEW,
                    "정책 연도 단서가 없어 원문 신청기간이 이번 공고인지 확인할 수 없음");
        }
        List<ApplicationPeriod> apiValid = api.stream().filter(p -> p.validationStatus() == ValidationStatus.VALID).toList();

        if (apiValid.isEmpty()) {
            boolean conflict = api.stream().anyMatch(p -> p.validationStatus() == ValidationStatus.INVALID);
            return new SupplementedPeriods(official, evidence(doc, evidenceText, conflict), SupplementOutcome.FILLED,
                    "API 신청기간이 없거나 오류여서 원문으로 보완");
        }
        if (dateRanges(apiValid).equals(dateRanges(official))) {
            return new SupplementedPeriods(api, evidence(doc, evidenceText, false), SupplementOutcome.CONFIRMED,
                    "API 신청기간과 원문이 일치");
        }
        if (doc.sourceType().confidence() == Confidence.HIGH) {
            return new SupplementedPeriods(official, evidence(doc, evidenceText, true), SupplementOutcome.OVERRIDDEN,
                    "API 신청기간과 공식 원문이 달라 공식 원문 우선");
        }
        List<ApplicationPeriod> review = api.stream()
                .map(p -> new ApplicationPeriod(p.periodType(), p.startDate(), p.endDate(), ValidationStatus.NEEDS_REVIEW, p.periodText()))
                .toList();
        return new SupplementedPeriods(review, evidence(doc, evidenceText, true), SupplementOutcome.NEEDS_REVIEW,
                "API 신청기간과 원문이 다름 (공식 출처가 아니라 자동 반영하지 않음)");
    }

    /** 연령 근거 적용 (API 텍스트·공식 원문 공통) */
    private SupplementedAge applyAge(AgeNormalization api, List<String> lines, OfficialDocument doc) {
        Evidence apiEvidence = apiEvidence(api.raw().toString(), api.dataConflict());
        List<AgeCandidate> candidates = distinct(ageExtractor.extract(lines));
        if (candidates.isEmpty()) {
            return new SupplementedAge(api, apiEvidence, SupplementOutcome.NOT_APPLIED, "연령 근거를 찾지 못함");
        }
        String evidenceText = candidates.stream().map(AgeCandidate::line).distinct().collect(Collectors.joining("\n"));
        Confidence confidence = doc.sourceType().confidence();

        if (confidence == Confidence.LOW || candidates.size() > 1) {
            // 이미 UNVERIFIED로 채운 값은 NEEDS_REVIEW로 바꾸지 않는다 (NEEDS_REVIEW는 일치하면 MATCHED로 판정되므로)
            ValidationStatus reviewStatus = api.validationStatus() == ValidationStatus.UNVERIFIED
                    ? ValidationStatus.UNVERIFIED : ValidationStatus.NEEDS_REVIEW;
            AgeNormalization review = withValidation(api, reviewStatus, api.dataConflict(),
                    candidates.size() > 1 ? "서로 다른 연령 조건이 여러 개 있음 (대상별 분리·예외 확인 필요)"
                            : "신뢰도가 낮은 출처라 자동 반영하지 않음");
            return new SupplementedAge(review, evidence(doc, evidenceText, api.dataConflict()),
                    SupplementOutcome.NEEDS_REVIEW, review.reason());
        }

        AgeCandidate c = candidates.get(0);
        AgeNormalization found = new AgeNormalization(c.status(), c.min(), c.max(), c.bornOnOrAfter(), c.bornOnOrBefore(),
                ValidationStatus.VALID, false, "근거 문장으로 확인", api.raw());

        if (api.validationStatus() == ValidationStatus.VALID) {
            if (api.sameRule(found)) {
                return new SupplementedAge(api, evidence(doc, evidenceText, false), SupplementOutcome.CONFIRMED,
                        "API 값과 근거가 일치");
            }
            if (confidence != Confidence.HIGH) {
                AgeNormalization review = withValidation(api, ValidationStatus.NEEDS_REVIEW, true,
                        "API 값과 근거가 다름 (공식 출처가 아니라 자동 반영하지 않음)");
                return new SupplementedAge(review, evidence(doc, evidenceText, true), SupplementOutcome.NEEDS_REVIEW,
                        review.reason());
            }
            AgeNormalization overridden = withValidation(found, ValidationStatus.VALID, true,
                    "API 값과 공식 원문이 달라 공식 원문 우선");
            return new SupplementedAge(overridden, evidence(doc, evidenceText, true), SupplementOutcome.OVERRIDDEN,
                    overridden.reason());
        }

        boolean conflict = api.dataConflict() || api.validationStatus() == ValidationStatus.INVALID;
        AgeNormalization filled = confidence == Confidence.HIGH
                ? withValidation(found, ValidationStatus.VALID, conflict, "API 값이 없거나 모순이어서 공식 원문으로 보완")
                : withValidation(found, ValidationStatus.UNVERIFIED, conflict,
                        "API 값이 없거나 모순이어서 근거로 보완 (공식 출처로 확인 전)");
        return new SupplementedAge(filled, evidence(doc, evidenceText, conflict), SupplementOutcome.FILLED, filled.reason());
    }

    /** 원문이 이 정책의 문서인지 확인한다: 정책명 포함 + (연도 단서가 있으면) 같은 연도 포함 */
    private static Match match(YouthPolicyRaw raw, OfficialDocument doc) {
        if (doc == null || doc.content() == null || doc.sourceType() == null) {
            return new Match(null, SupplementOutcome.NOT_APPLIED, "원문 없음");
        }
        List<String> lines = OfficialText.lines(doc.content());
        if (!titleMatches(raw.plcyNm(), lines)) {
            return new Match(null, SupplementOutcome.DOCUMENT_MISMATCH, "원문에서 정책명을 찾지 못함 (다른 정책·페이지 가능성)");
        }
        Set<String> expected = expectedYears(raw);
        if (!expected.isEmpty() && Collections.disjoint(expected, years(String.join(" ", lines)))) {
            return new Match(null, SupplementOutcome.DOCUMENT_MISMATCH,
                    "원문에 이 정책의 연도(" + String.join(",", expected) + ")가 없음 (다른 연도 공고 가능성)");
        }
        return new Match(lines, null, null);
    }

    /** 이 정책의 연도 단서: 정책명의 연도, API 신청기간의 연도. 등록 연도·현재 연도로 대체하지 않는다. */
    static Set<String> expectedYears(YouthPolicyRaw raw) {
        Set<String> result = new TreeSet<>(years(raw.plcyNm()));
        result.addAll(years(raw.aplyYmd()));
        return result;
    }

    private static Set<String> years(String text) {
        Set<String> result = new TreeSet<>();
        if (text == null) {
            return result;
        }
        Matcher m = YEAR.matcher(text);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    /** 정책명 그대로 또는 연도·괄호를 뺀 정책명이 원문에 있는지 (공백 무시) */
    static boolean titleMatches(String title, List<String> lines) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String text = OfficialText.compact(String.join("", lines));
        String full = OfficialText.compact(title);
        String core = OfficialText.compact(title.replaceAll("\\d{4}\\s*년(도)?", "").replaceAll("[\\[(（【][^\\])）】]*[\\])）】]", ""));
        return text.contains(full) || (core.length() >= 4 && text.contains(core));
    }

    private static List<AgeCandidate> distinct(List<AgeCandidate> candidates) {
        List<AgeCandidate> result = new ArrayList<>();
        for (AgeCandidate c : candidates) {
            if (result.stream().noneMatch(c::sameRule)) {
                result.add(c);
            }
        }
        return result;
    }

    private static Set<List<Object>> dateRanges(List<ApplicationPeriod> periods) {
        return periods.stream()
                .map(p -> Arrays.<Object>asList(p.startDate(), p.endDate()))
                .collect(Collectors.toSet());
    }

    private static AgeNormalization withValidation(AgeNormalization a, ValidationStatus validation, boolean conflict, String reason) {
        return new AgeNormalization(a.status(), a.min(), a.max(), a.bornOnOrAfter(), a.bornOnOrBefore(),
                validation, conflict, reason, a.raw());
    }

    private static Evidence evidence(OfficialDocument doc, String text, boolean conflict) {
        return new Evidence(doc.sourceType(), doc.url(), doc.contentHash(), text, doc.sourceType().confidence(),
                conflict, doc.fetchedAt());
    }

    private static Evidence apiEvidence(String text, boolean conflict) {
        return new Evidence(SourceType.YOUTH_CENTER_API, null, null, text, SourceType.YOUTH_CENTER_API.confidence(),
                conflict, null);
    }

    private record Match(List<String> lines, SupplementOutcome outcome, String reason) {
    }
}
