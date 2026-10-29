package com.team3.backend.search.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 통합 검색어를 후보 추출(B1)용 토큰으로 해석한다.
 *
 * 규칙은 검색 품질 실험에서 확정한 B1 그대로다. 제목 비교 대상은 DB 생성 컬럼
 * search_title_norm(= lower(normalize(title, NFKC)))과 search_title_strip(공백·가운뎃점·하이픈 제거)이므로,
 * 검색어도 같은 NFKC·소문자 정규화를 거친 뒤 구분자를 공백으로 바꿔 토큰으로 나눈다.
 * 토큰은 공백·구분자·하이픈이 없으므로 search_title_strip과 그대로 비교할 수 있다.
 *
 * 기존 제목 부분 일치(A)는 원래 검색어를 trim·소문자로만 바꿔 그대로 유지한다.
 */
public final class SearchKeyword {

    /** 토큰이 이보다 많으면 토큰 AND 대신 공백을 무시한 전체 구문 일치만 더한다. */
    static final int MAX_TOKENS = 5;

    /** 표기만 다른 같은 대상. 한 토큰이 그룹에 속하면 그룹의 모든 표기 중 하나만 맞아도 된다. */
    private static final List<List<String>> SYNONYMS = List.of(
            List.of("파이썬", "python"), List.of("컴활", "컴퓨터활용능력"), List.of("챗gpt", "chatgpt"),
            List.of("리액트", "react"), List.of("스프링", "spring"), List.of("엑셀", "excel"),
            List.of("파워포인트", "ppt"));

    /** 정책 keywords·훈련구분 비교에서 빼는 범용어. 거의 모든 공고에 붙어 후보만 늘린다. */
    private static final Set<String> GENERIC = Set.of("지원", "청년", "사업", "운영", "모집", "과정", "훈련", "교육", "프로그램");

    /** c++처럼 영문 뒤에 붙은 +는 남기고, 단독 +는 구분자로 본다. */
    private static final Pattern LONE_PLUS = Pattern.compile("(?<![a-z+])\\+");
    private static final Pattern SEPARATORS = Pattern.compile(
            "[\\s/,·・‧ᆞㆍ|()\\[\\]{}「」『』<>\"'`~!?:;&]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SHORT_LATIN = Pattern.compile("[a-z0-9]{1,2}");
    private static final Pattern LATIN_OR_DIGIT = Pattern.compile("[a-z0-9]");

    /** 동의어 표기 조합이 이보다 많으면 관련도 구문 판정에 원래 토큰만 쓴다. */
    static final int MAX_PHRASE_COMBOS = 8;
    private static final String REGEX_SPECIAL = "()[]{}?*+-|^$\\.&~# \t\n\r\u000B\f";

    private final String text;
    private final List<Token> tokens;

    private SearchKeyword(String text, List<Token> tokens) {
        this.text = text;
        this.tokens = tokens;
    }

    /** 공백만 있거나 null이면 검색어가 없는 것으로 보고 null을 돌려준다 (기존 StringUtils.hasText 기준). */
    public static SearchKeyword parse(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String token : WHITESPACE.split(normalize(keyword))) {
            if (!token.isEmpty()) {
                distinct.add(token);
            }
        }
        List<Token> tokens = new ArrayList<>();
        for (String token : distinct) {
            tokens.add(new Token(token, variants(token)));
        }
        return new SearchKeyword(keyword.trim().toLowerCase(Locale.ROOT), List.copyOf(tokens));
    }

    static String normalize(String raw) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
        normalized = LONE_PLUS.matcher(normalized).replaceAll(" ");
        normalized = SEPARATORS.matcher(normalized).replaceAll(" ");
        return normalized.replace("-", "");
    }

    private static List<String> variants(String token) {
        for (List<String> group : SYNONYMS) {
            if (group.contains(token)) {
                List<String> variants = new ArrayList<>();
                variants.add(token);
                group.stream().filter(synonym -> !synonym.equals(token)).forEach(variants::add);
                return List.copyOf(variants);
            }
        }
        return List.of(token);
    }

    /** 기존 제목 부분 일치(A)의 LIKE 패턴. 이스케이프 문자는 '\\'다. */
    public String titleContainsPattern() {
        return contains(text);
    }

    public List<Token> tokens() {
        return tokens;
    }

    /** 특수문자만 입력해 토큰이 하나도 없으면 기존 제목 부분 일치(A)만 쓴다. */
    public boolean hasTokens() {
        return !tokens.isEmpty();
    }

    public boolean phraseMode() {
        return tokens.size() > MAX_TOKENS;
    }

    /** 구문 모드의 공백 무시 전체 구문. search_title_strip과 비교한다. */
    public String phrase() {
        return String.join("", tokens.stream().map(Token::value).toList());
    }

    /** 관련도 보너스(정책 keywords·훈련구분·NCS)는 앞의 MAX_TOKENS개 토큰만 센다. 구문 모드도 같다. */
    public List<Token> bonusTokens() {
        return tokens.subList(0, Math.min(MAX_TOKENS, tokens.size()));
    }

    /**
     * 제목 관련도(tier)의 완전·접두·구문 판정에 쓸 검색어 형태. 토큰마다 동의어 표기 중 하나를 고른 조합이며
     * 앞 토큰이 천천히 바뀌는 순서다 (itertools.product와 같다). 조합이 MAX_PHRASE_COMBOS개를 넘으면 원래 토큰만 쓴다.
     */
    public List<PhraseForm> phraseForms() {
        long combinations = 1;
        for (Token token : tokens) {
            combinations *= token.variants().size();
        }
        if (combinations > MAX_PHRASE_COMBOS) {
            return List.of(PhraseForm.of(tokens.stream().map(Token::value).toList()));
        }
        List<List<String>> forms = new ArrayList<>();
        forms.add(List.of());
        for (Token token : tokens) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> prefix : forms) {
                for (String variant : token.variants()) {
                    List<String> form = new ArrayList<>(prefix);
                    form.add(variant);
                    next.add(form);
                }
            }
            forms = next;
        }
        return forms.stream().map(PhraseForm::of).toList();
    }

    /**
     * Python re.escape(3.7+)와 같은 정규식 이스케이프. 관련도 정규식을 검증된 SQL 프로토타입과 같은 문자열로 만든다.
     * PostgreSQL ARE에서 역슬래시 뒤의 영숫자가 아닌 문자는 그 문자 자체로 해석된다.
     */
    public static String regexEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (REGEX_SPECIAL.indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /**
     * 관련도 판정용 검색어 형태.
     *
     * @param spaced 토큰을 공백으로 이은 형태 (제목의 구분자를 공백으로 바꾼 값과 비교)
     * @param joined 토큰을 붙인 형태 (search_title_strip·앞머리 제거 제목과 비교)
     */
    public record PhraseForm(String spaced, String joined) {

        static PhraseForm of(List<String> parts) {
            return new PhraseForm(String.join(" ", parts), String.join("", parts));
        }

        /** 검색어 앞이 영문·숫자이면 그 앞에 영문 경계를 요구한다 (java가 javascript에 걸리지 않게). */
        public String leftBoundary() {
            return !joined.isEmpty() && LATIN_OR_DIGIT.matcher(joined.substring(0, 1)).matches()
                    ? "(^|[^a-z0-9])" : "";
        }

        /** 검색어 끝이 영문·숫자이면 그 뒤에 영문 경계를 요구한다. */
        public String rightBoundary() {
            return !joined.isEmpty() && LATIN_OR_DIGIT.matcher(joined.substring(joined.length() - 1)).matches()
                    ? "([^a-z0-9]|$)" : "";
        }
    }

    /** '%값%' LIKE 패턴. 이스케이프 문자는 '\\'다. */
    public static String contains(String value) {
        return "%" + escapeLike(value) + "%";
    }

    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 검색어 토큰과 동의어 표기.
     *
     * @param value    정규화된 토큰
     * @param variants 토큰 자신이 맨 앞이고 같은 동의어 그룹의 다른 표기가 뒤따른다
     */
    public record Token(String value, List<String> variants) {

        /** 영문·숫자 1~2자. 부분 일치 대신 영문 단어 경계로 비교한다 (ai가 email·said에 걸리지 않게). */
        public static boolean shortLatin(String variant) {
            return SHORT_LATIN.matcher(variant).matches();
        }

        /** search_title_norm에 쓰는 영문 단어 경계 정규식. shortLatin 표기에만 쓴다 (영문·숫자라 이스케이프가 필요 없다). */
        public static String wordBoundaryRegex(String variant) {
            return "(^|[^a-z0-9])" + variant + "([^a-z0-9]|$)";
        }

        /** 정책 keywords 항목 접두 비교에 쓸 표기. 범용어 토큰이거나 1자 표기는 뺀다. */
        public List<String> policyKeywordVariants() {
            return matchingVariants(2);
        }

        /** 훈련구분 부분 비교에 쓸 표기. 범용어 토큰이거나 4자 미만 표기는 뺀다. */
        public List<String> trainingTargetVariants() {
            return matchingVariants(4);
        }

        private List<String> matchingVariants(int minLength) {
            if (GENERIC.contains(value)) {
                return List.of();
            }
            return variants.stream()
                    .filter(variant -> variant.codePointCount(0, variant.length()) >= minLength)
                    .toList();
        }
    }
}
