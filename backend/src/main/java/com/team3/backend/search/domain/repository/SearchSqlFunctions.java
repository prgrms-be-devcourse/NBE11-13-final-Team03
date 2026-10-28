package com.team3.backend.search.domain.repository;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * 검색 후보 추출(B1)이 쓰는 PostgreSQL 전용 식을 Criteria 함수로 등록한다.
 * normalize(x, NFKC)의 NFKC와 정규식 연산자 ~ 는 Criteria 함수 인자로 표현할 수 없어 패턴으로 둔다.
 * PostgreSQL에서만 호출한다 (H2에는 normalize가 없다). META-INF/services로 Hibernate가 읽는다.
 */
public class SearchSqlFunctions implements FunctionContributor {

    /** search_title_strip 생성 컬럼과 같은 식. 제목 외 컬럼(정책 keywords, 훈련구분)을 같은 형태로 맞춘다. */
    public static final String STRIP = "search_strip";

    /** 대소문자를 구분하는 POSIX 정규식 일치(~). 소문자로 정규화된 값에만 쓴다. */
    public static final String REGEX_MATCH = "search_regex_match";

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        var registry = functionContributions.getFunctionRegistry();
        var types = functionContributions.getTypeConfiguration().getBasicTypeRegistry();
        registry.registerPattern(STRIP, "regexp_replace(lower(normalize(?1, NFKC)), '[\\s·・‧ᆞ\\-]', '', 'g')",
                types.resolve(StandardBasicTypes.STRING));
        registry.registerPattern(REGEX_MATCH, "(?1 ~ ?2)", types.resolve(StandardBasicTypes.BOOLEAN));
    }
}
