package com.team3.dataservice.opportunity.normalization.official;

import com.team3.dataservice.opportunity.normalization.AgeStatus;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원문 줄에서 연령 조건을 찾는다. 규칙 기반이며, 해석이 애매한 것은 만들지 않는다.
 *
 * - 연령 문맥(연령, 나이, 대상, 자격, 신청, "만 N세")이 있는 줄만 본다. 숫자만 있는 줄은 무시한다.
 * - "미만"은 최대-1, "초과"는 최소+1로 바꾼다 (만 나이는 정수).
 * - 찾을 수 있는 형태: "만 19세 이상 34세 이하", "만 19~34세", "만 18세 이상", "39세 미만",
 *   "1986년 이후 출생", "1986.1.1. ~ 2007.12.31. 출생자", "연령 제한 없음/무관"
 */
@Component
public class AgeEvidenceExtractor {

    private static final Pattern CONTEXT = Pattern.compile("연령|나이|대상|자격|신청|만\\s*\\d{1,2}\\s*세");
    private static final Pattern NO_LIMIT = Pattern.compile("(연령|나이)\\s*(제한\\s*)?(없음|무관|제한\\s*없|불문)");

    private static final String AGE = "(?:만\\s*)?(\\d{1,3})\\s*세";
    private static final Pattern RANGE_WORDS = Pattern.compile(AGE + "\\s*이상\\s*[,~∼～\\-]?\\s*" + AGE + "\\s*(이하|미만)");
    private static final Pattern RANGE_TILDE = Pattern.compile("(?:만\\s*)?(\\d{1,3})\\s*세?\\s*[~∼～\\-]\\s*" + AGE + "\\s*(이하|미만)?");
    private static final Pattern MIN_ONLY = Pattern.compile(AGE + "\\s*(이상|초과)");
    private static final Pattern MAX_ONLY = Pattern.compile(AGE + "\\s*(이하|미만|까지)");

    private static final String BIRTH_DATE = "(\\d{4})\\s*(?:년|\\.|-)\\s*(?:(\\d{1,2})\\s*(?:월|\\.|-)\\s*(\\d{1,2})\\s*(?:일|\\.)?)?";
    private static final Pattern BIRTH_RANGE = Pattern.compile(BIRTH_DATE + "\\s*[~∼～\\-]\\s*" + BIRTH_DATE + "\\s*(?:사이\\s*)?(?:에\\s*)?출생");
    private static final Pattern BIRTH_AFTER = Pattern.compile(BIRTH_DATE + "\\s*(?:생\\s*)?(이후|이전)\\s*(?:에\\s*)?출생");

    public List<AgeCandidate> extract(List<String> lines) {
        List<AgeCandidate> candidates = new ArrayList<>();
        for (String line : lines) {
            if (!CONTEXT.matcher(line).find() && !line.contains("출생")) {
                continue;
            }
            extractLine(line, candidates);
        }
        return candidates;
    }

    private void extractLine(String line, List<AgeCandidate> out) {
        if (NO_LIMIT.matcher(line).find()) {
            out.add(new AgeCandidate(AgeStatus.NO_LIMIT, null, null, null, null, line));
            return;
        }
        if (extractBirth(line, out)) {
            return;
        }
        String rest = line;
        rest = extractRanges(RANGE_WORDS, rest, line, out);
        rest = extractRanges(RANGE_TILDE, rest, line, out);
        Matcher min = MIN_ONLY.matcher(rest);
        while (min.find()) {
            Integer value = age(min.group(1));
            if (value != null) {
                int adjusted = "초과".equals(min.group(2)) ? value + 1 : value;
                out.add(new AgeCandidate(AgeStatus.MIN_ONLY, adjusted, null, null, null, line));
            }
        }
        Matcher max = MAX_ONLY.matcher(rest);
        while (max.find()) {
            Integer value = age(max.group(1));
            if (value != null) {
                int adjusted = "미만".equals(max.group(2)) ? value - 1 : value;
                out.add(new AgeCandidate(AgeStatus.MAX_ONLY, null, adjusted, null, null, line));
            }
        }
    }

    /** 범위를 찾아 후보로 넣고, 찾은 부분을 지운 나머지 문자열을 반환한다 (이상/이하 단독 패턴이 다시 잡지 않도록) */
    private String extractRanges(Pattern pattern, String text, String line, List<AgeCandidate> out) {
        Matcher m = pattern.matcher(text);
        StringBuilder rest = new StringBuilder();
        while (m.find()) {
            Integer min = age(m.group(1));
            Integer max = age(m.group(2));
            if (min != null && max != null) {
                int adjustedMax = "미만".equals(m.group(3)) ? max - 1 : max;
                if (min <= adjustedMax) {
                    out.add(new AgeCandidate(AgeStatus.RANGE, min, adjustedMax, null, null, line));
                }
            }
            m.appendReplacement(rest, " ");
        }
        m.appendTail(rest);
        return rest.toString();
    }

    private boolean extractBirth(String line, List<AgeCandidate> out) {
        Matcher range = BIRTH_RANGE.matcher(line);
        if (range.find()) {
            LocalDate from = date(range.group(1), range.group(2), range.group(3), true);
            LocalDate to = date(range.group(4), range.group(5), range.group(6), false);
            if (from != null && to != null && !from.isAfter(to)) {
                out.add(new AgeCandidate(AgeStatus.DATE_BOUND, null, null, from, to, line));
                return true;
            }
        }
        Matcher single = BIRTH_AFTER.matcher(line);
        if (single.find()) {
            boolean after = "이후".equals(single.group(4));
            LocalDate date = date(single.group(1), single.group(2), single.group(3), after);
            if (date != null) {
                out.add(new AgeCandidate(AgeStatus.DATE_BOUND, null, null, after ? date : null, after ? null : date, line));
                return true;
            }
        }
        return false;
    }

    private static Integer age(String value) {
        int age = Integer.parseInt(value);
        return age > 0 && age <= 120 ? age : null;
    }

    /** 연도만 있으면 시작 기준은 1월 1일, 끝 기준은 12월 31일 */
    private static LocalDate date(String year, String month, String day, boolean startOfYear) {
        try {
            int y = Integer.parseInt(year);
            if (y < 1900 || y > 2100) {
                return null;
            }
            if (month == null) {
                return startOfYear ? LocalDate.of(y, 1, 1) : LocalDate.of(y, 12, 31);
            }
            return LocalDate.of(y, Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}
