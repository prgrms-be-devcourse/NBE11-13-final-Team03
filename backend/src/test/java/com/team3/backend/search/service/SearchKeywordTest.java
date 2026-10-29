package com.team3.backend.search.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 기대값은 검색 품질 실험의 B1 프로토타입(gen_v2 tokenize/variants)이 같은 입력에 낸 결과다. */
class SearchKeywordTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n "})
    void blankKeywordMeansNoKeyword(String keyword) {
        assertThat(SearchKeyword.parse(keyword)).isNull();
    }

    @Test
    void nfkcLowercaseAndCompatibilityJamoSeparator() {
        assertThat(values("ＡＩ 청년ㆍ월세")).containsExactly("ai", "청년", "월세");
        assertThat(values("청년 도약")).containsExactly("청년", "도약");
    }

    @Test
    void separatorsSplitTokensAndHyphenIsRemoved() {
        assertThat(values("청년/주거,금융")).containsExactly("청년", "주거", "금융");
        assertThat(values("[원패스] 컴활(2급)")).containsExactly("원패스", "컴활", "2급");
        assertThat(values("React-Native")).containsExactly("reactnative");
        assertThat(values("  파이썬 기초  ")).containsExactly("파이썬", "기초");
    }

    @Test
    void plusAfterLatinIsKeptButLonePlusSeparates() {
        assertThat(values("c++ 프로그래밍")).containsExactly("c++", "프로그래밍");
        assertThat(values("a+b 수학")).containsExactly("a+b", "수학");
        assertThat(values("수학 + 과학")).containsExactly("수학", "과학");
    }

    @Test
    void duplicateTokensAreDroppedKeepingOrder() {
        assertThat(values("엑셀 엑셀 EXCEL")).containsExactly("엑셀", "excel");
    }

    @Test
    void synonymGroupsExpandWithTheTokenFirst() {
        SearchKeyword keyword = SearchKeyword.parse("파이썬 ChatGPT ppt 기초");

        assertThat(keyword.tokens()).extracting(SearchKeyword.Token::variants).containsExactly(
                List.of("파이썬", "python"), List.of("chatgpt", "챗gpt"), List.of("ppt", "파워포인트"), List.of("기초"));
        assertThat(SearchKeyword.parse("컴활").tokens().getFirst().variants()).containsExactly("컴활", "컴퓨터활용능력");
    }

    @Test
    void onlySeparatorCharactersLeaveNoTokensButKeepTitleContains() {
        SearchKeyword keyword = SearchKeyword.parse("!!!");

        assertThat(keyword.hasTokens()).isFalse();
        assertThat(keyword.titleContainsPattern()).isEqualTo("%!!!%");
        // 구분자가 아닌 특수문자는 프로토타입과 같이 토큰으로 남는다
        assertThat(values("@#$%")).containsExactly("@#$%");
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(SearchKeyword.parse("100%").titleContainsPattern()).isEqualTo("%100\\%%");
        assertThat(SearchKeyword.parse("Under_Score").titleContainsPattern()).isEqualTo("%under\\_score%");
        assertThat(SearchKeyword.contains("a\\b")).isEqualTo("%a\\\\b%");
    }

    @Test
    void titleContainsKeepsTheOriginalTrimmedLowercaseKeyword() {
        // 기존 제목 부분 일치(A)는 정규화하지 않는다
        assertThat(SearchKeyword.parse("  청년 월세ㆍ지원 ").titleContainsPattern()).isEqualTo("%청년 월세ㆍ지원%");
    }

    @Test
    void moreThanFiveTokensSwitchToPhraseMode() {
        SearchKeyword phrase = SearchKeyword.parse("데이터 분석 실무 과정 온라인 수료 지원");
        SearchKeyword five = SearchKeyword.parse("데이터 분석 실무 과정 온라인");

        assertThat(phrase.phraseMode()).isTrue();
        assertThat(phrase.phrase()).isEqualTo("데이터분석실무과정온라인수료지원");
        assertThat(five.phraseMode()).isFalse();
    }

    @Test
    void shortLatinUsesWordBoundary() {
        assertThat(SearchKeyword.Token.shortLatin("ai")).isTrue();
        assertThat(SearchKeyword.Token.shortLatin("3d")).isTrue();
        assertThat(SearchKeyword.Token.shortLatin("ppt")).isFalse();
        assertThat(SearchKeyword.Token.shortLatin("c+")).isFalse();
        assertThat(SearchKeyword.Token.shortLatin("엑셀")).isFalse();
        assertThat(SearchKeyword.Token.wordBoundaryRegex("ai")).isEqualTo("(^|[^a-z0-9])ai([^a-z0-9]|$)");
    }

    @Test
    void policyKeywordAndTrainingTargetSkipGenericTokensAndShortVariants() {
        List<SearchKeyword.Token> tokens = SearchKeyword.parse("지원 주거 ai 파이썬 컴활").tokens();

        assertThat(tokens.get(0).policyKeywordVariants()).isEmpty();        // 범용어
        assertThat(tokens.get(1).policyKeywordVariants()).containsExactly("주거");
        assertThat(tokens.get(1).trainingTargetVariants()).isEmpty();       // 4자 미만
        assertThat(tokens.get(2).policyKeywordVariants()).containsExactly("ai");
        assertThat(tokens.get(3).trainingTargetVariants()).containsExactly("python");
        assertThat(tokens.get(4).trainingTargetVariants()).containsExactly("컴퓨터활용능력");
    }

    @Test
    void phraseFormsCombineSynonymsInProductOrderUpToEight() {
        assertThat(SearchKeyword.parse("파이썬 엑셀 기초").phraseForms()).containsExactly(
                new SearchKeyword.PhraseForm("파이썬 엑셀 기초", "파이썬엑셀기초"),
                new SearchKeyword.PhraseForm("파이썬 excel 기초", "파이썬excel기초"),
                new SearchKeyword.PhraseForm("python 엑셀 기초", "python엑셀기초"),
                new SearchKeyword.PhraseForm("python excel 기초", "pythonexcel기초"));
        // 조합이 16개라 8개를 넘으면 원래 토큰만 쓴다
        assertThat(SearchKeyword.parse("컴활 엑셀 파이썬 ppt").phraseForms())
                .containsExactly(new SearchKeyword.PhraseForm("컴활 엑셀 파이썬 ppt", "컴활엑셀파이썬ppt"));
    }

    @Test
    void regexEscapeAndBoundariesMatchThePrototype() {
        assertThat(SearchKeyword.regexEscape("c++")).isEqualTo("c\\+\\+");
        assertThat(SearchKeyword.regexEscape("a.b")).isEqualTo("a\\.b");
        assertThat(SearchKeyword.regexEscape("100%")).isEqualTo("100%");
        assertThat(SearchKeyword.regexEscape("x-y z")).isEqualTo("x\\-y\\ z");
        assertThat(SearchKeyword.regexEscape("(일반)")).isEqualTo("\\(일반\\)");
        assertThat(SearchKeyword.regexEscape("a#b~c&d")).isEqualTo("a\\#b\\~c\\&d");

        SearchKeyword.PhraseForm latinStart = new SearchKeyword.PhraseForm("c++", "c++");
        assertThat(latinStart.leftBoundary()).isEqualTo("(^|[^a-z0-9])");
        assertThat(latinStart.rightBoundary()).isEmpty();
        SearchKeyword.PhraseForm korean = new SearchKeyword.PhraseForm("(일반)", "(일반)");
        assertThat(korean.leftBoundary()).isEmpty();
        assertThat(korean.rightBoundary()).isEmpty();
        assertThat(new SearchKeyword.PhraseForm("a.b", "a.b").rightBoundary()).isEqualTo("([^a-z0-9]|$)");
    }

    @Test
    void bonusTokensAreTheFirstFive() {
        assertThat(SearchKeyword.parse("데이터 분석 실무 과정 온라인 수료").bonusTokens())
                .extracting(SearchKeyword.Token::value).containsExactly("데이터", "분석", "실무", "과정", "온라인");
    }

    private static List<String> values(String keyword) {
        return SearchKeyword.parse(keyword).tokens().stream().map(SearchKeyword.Token::value).toList();
    }
}
