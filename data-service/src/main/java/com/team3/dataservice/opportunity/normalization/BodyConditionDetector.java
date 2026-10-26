package com.team3.dataservice.opportunity.normalization;

import com.team3.dataservice.opportunity.normalization.dto.YouthPolicyRaw;
import com.team3.dataservice.opportunity.normalization.official.OfficialText;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 정책 본문(설명·지원내용·키워드·신청방법·심사방법·제출서류·기타)에만 적힌, 판정하지 않는 자격 조건을 찾는다.
 *
 * 판정 엔진은 주거(무주택)·재산·병역 조건을 판정하지 않는다. 이런 조건이 추가신청자격(addAplyQlfcCndCn)·참여제한대상(ptcpPrpTrgtCn)에
 * 있으면 원문 확인 조건이 되지만, 본문에만 있으면 판정이 몰라 ELIGIBLE이 나올 수 있다 (실데이터 2026-10-23: 판정상 ELIGIBLE이
 * 가능한 청년정책 634건 중 14건). 여기서 찾은 조건은 원문 확인 조건(UNSTRUCTURED)으로 저장해 최대 POSSIBLY_ELIGIBLE이 되게 한다.
 *
 * 자격 문맥의 표현만 찾는다. 다음은 자격 조건이 아니어서 찾지 않는다 (같은 실데이터에서 확인한 오탐):
 * 지식재산권, 자산취득비·자산성·재산성 물품(지원금 사용처), 부동산업(창업 업종 제한), 자산형성·자산관리(정책 이름),
 * 세대주 관계·병역 사항 표기(제출 서류 안내), 선제대응·국제대회의 "제대",
 * 제대군인(중복 수급 제외 목록의 "제대군인 전직지원금", 제대군인 연령 연장 안내), 군필자(상한 연령 연장 증빙).
 * 혜택 문맥(재산세 감면·면제·환급·경감처럼 감지어 바로 뒤에 혜택 표현)도 찾지 않는다 (예: 기업 지원 "사업장 부동산 재산세 감면").
 *
 * 제출서류 문맥(소득·재산 신고서, 재산세 과세증명서, 무주택 확인서)은 일부러 찾는다. 자격 판단에 쓰는 서류여서, 실데이터에서
 * 청년월세지원 2건은 재산 기준이 제출서류의 "소득·재산 신고서"에만 드러난다 (서류 문맥을 빼면 실제 조건 14건 중 2건을 놓침).
 *
 * 한계: 키워드 규칙이라 자격이 아닌 문장을 잡을 수 있다. 그래서 찾은 조건은 NEEDS_REVIEW로 저장하고 근거 줄을 남긴다
 * (관리자 확인용 SQL은 data-service/README.md). 놓치거나 더 잡더라도 결과는 ELIGIBLE과 POSSIBLY_ELIGIBLE 사이에서만
 * 바뀐다 (더 잡으면 보수적인 쪽).
 */
public final class BodyConditionDetector {

    /** 찾는 조건 종류. 이름이 조건 expression의 kinds 값이 된다 */
    /** 감지어 바로 뒤가 혜택 표현이면 자격 조건이 아니다 (재산세 감면 등) */
    private static final String NOT_BENEFIT = "(?!\\s*(감면|면제|환급|경감))";

    public enum Kind {
        HOUSING(Pattern.compile("무주택|주택을?\\s*소유하지\\s*않")),
        PROPERTY(Pattern.compile("소득\\s*[·ㆍ･,/및]?\\s*(재산|자산)" + NOT_BENEFIT
                + "|총?자산\\s*가액|자동차\\s*가액|\\(재산\\)|재산세" + NOT_BENEFIT
                + "|재산\\s*(기준|요건|가액|조사|조회|합계)")),
        MILITARY(Pattern.compile("병역\\s*미필|미필자|병역\\s*의무를?\\s*(마친|이행|필)"
                + "|(?<![가-힣])전역\\s*(예정\\s*)?(장병|군인|자)|현역\\s*장병"));

        private final Pattern pattern;

        Kind(Pattern pattern) {
            this.pattern = pattern;
        }
    }

    /** 찾은 조건 종류(선언 순서), 근거 줄("[필드] 줄", 종류마다 처음 찾은 한 줄), 근거가 있는 필드 */
    public record Detection(List<Kind> kinds, String evidenceText, List<String> sourceFields) {

        public boolean isEmpty() {
            return kinds.isEmpty();
        }

        public List<String> kindNames() {
            return kinds.stream().map(Enum::name).toList();
        }
    }

    private BodyConditionDetector() {
    }

    public static Detection detect(YouthPolicyRaw raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("plcyExplnCn", raw.plcyExplnCn());
        fields.put("plcySprtCn", raw.plcySprtCn());
        fields.put("plcyKywdNm", raw.plcyKywdNm());
        fields.put("plcyAplyMthdCn", raw.plcyAplyMthdCn());
        fields.put("srngMthdCn", raw.srngMthdCn());
        fields.put("sbmsnDcmntCn", raw.sbmsnDcmntCn());
        fields.put("etcMttrCn", raw.etcMttrCn());

        Map<Kind, String> found = new EnumMap<>(Kind.class);
        Set<String> sourceFields = new LinkedHashSet<>();
        fields.forEach((name, value) -> {
            for (String line : OfficialText.lines(value)) {
                for (Kind kind : Kind.values()) {
                    if (!found.containsKey(kind) && kind.pattern.matcher(line).find()) {
                        found.put(kind, "[" + name + "] " + line);
                        sourceFields.add(name);
                    }
                }
            }
        });
        return new Detection(List.copyOf(found.keySet()), String.join("\n", new LinkedHashSet<>(found.values())),
                List.copyOf(sourceFields));
    }
}
