package com.team3.backend.search.domain.repository;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * 검색 후보 추출(B1)이 쓰는 PostgreSQL 전용 식을 Criteria 함수로 등록한다.
 * 정규식 연산자 ~ 는 Criteria 함수 인자로 표현할 수 없어 패턴으로 둔다.
 * PostgreSQL에서만 호출한다. META-INF/services로 Hibernate가 읽는다.
 */
public class SearchSqlFunctions implements FunctionContributor {

    /** 대소문자를 구분하는 POSIX 정규식 일치(~). 소문자로 정규화된 값에만 쓴다. */
    public static final String REGEX_MATCH = "search_regex_match";

    /** 정규식에 맞는 부분을 모두 바꾼다 (regexp_replace(값, 정규식, 대체, 'g')). 관련도용 제목 형태를 만든다. */
    public static final String REGEX_REPLACE_ALL = "search_regex_replace_all";

    /**
     * 훈련 NCS 분류 세그먼트 중 하나가 값과 같은가 (관련도 보너스). 인자: 중분류, 세 번째 분류, 비교 값.
     * 두 분류를 가운뎃점으로 이어 NFKC·소문자로 바꾸고 공백·하이픈을 지운 뒤 가운뎃점으로 나눈 세그먼트와 비교한다.
     */
    public static final String NCS_SEGMENT_MATCH = "search_ncs_segment_match";

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        var registry = functionContributions.getFunctionRegistry();
        var types = functionContributions.getTypeConfiguration().getBasicTypeRegistry();
        registry.registerPattern(REGEX_MATCH, "(?1 ~ ?2)", types.resolve(StandardBasicTypes.BOOLEAN));
        registry.registerPattern(REGEX_REPLACE_ALL, "regexp_replace(?1, ?2, ?3, 'g')",
                types.resolve(StandardBasicTypes.STRING));
        registry.registerPattern(NCS_SEGMENT_MATCH, "(?3 = any(string_to_array(regexp_replace(lower(normalize("
                        + "coalesce(?1, '') || '·' || coalesce(?2, ''), NFKC)), '[\\s\\-]', '', 'g'), '·')))",
                types.resolve(StandardBasicTypes.BOOLEAN));
    }

    /**
     * search_title_strip 생성 컬럼과 같은 정규화 식. 제목 외 컬럼(정책 keywords, 훈련구분)을 같은 형태로 맞출 때 쓴다.
     * normalize(x, NFKC)의 NFKC는 Criteria 함수 인자로 표현할 수 없어 네이티브 조회(DetailValueNames)에서만 쓴다.
     */
    public static String stripSql(String argument) {
        return "regexp_replace(lower(normalize(" + argument + ", NFKC)), '[\\s·・‧ᆞ\\-]', '', 'g')";
    }
}
