package com.team3.backend.search.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * PostgreSQL 검색 후보 추출(B1)이 기대는 생성 컬럼 search_title_norm·search_title_strip이 실제로 있는지 확인한다.
 *
 * database/scripts/search-title-columns.sql보다 backend가 먼저 뜨면 ddl-auto:update가 같은 이름의
 * 일반 컬럼(NULL)을 만든다. 그 상태로 검색하면 확장 후보가 조용히 빠지므로, 기존 방식으로 넘어가지 않고 검색을 실패시킨다.
 * 확인은 한 번 성공하면 다시 하지 않는다. 실패는 기억하지 않아 스크립트를 적용하면 재기동 없이 다음 검색부터 통과한다.
 * Hibernate 문장이 아닌 JDBC로 조회해 검색 질의 수 계측에 섞이지 않게 한다.
 */
@Component
public class SearchTitleColumns {

    private static final String GENERATED_COUNT = """
            SELECT count(*)
            FROM pg_attribute
            WHERE attrelid = 'opportunities'::regclass
              AND attname IN ('search_title_norm', 'search_title_strip')
              AND attgenerated = 's'
              AND NOT attisdropped
            """;

    private final JdbcTemplate jdbc;
    private volatile boolean verified;

    public SearchTitleColumns(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void verify() {
        if (verified) {
            return;
        }
        Integer generated = jdbc.queryForObject(GENERATED_COUNT, Integer.class);
        if (generated == null || generated != 2) {
            throw new IllegalStateException("opportunities.search_title_norm/search_title_strip generated columns are "
                    + "missing or were created as regular columns. Apply database/scripts/search-title-columns.sql "
                    + "and check it with search-title-columns-check.sql. generatedColumns=" + generated);
        }
        verified = true;
    }
}
