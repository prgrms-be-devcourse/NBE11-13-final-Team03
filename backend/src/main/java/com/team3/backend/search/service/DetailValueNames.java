package com.team3.backend.search.service;

import com.team3.backend.search.domain.repository.SearchSqlFunctions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 검색 후보 추출(B1)이 정규화해 비교하는 상세 컬럼의 서로 다른 원문 값과 그 정규화 값.
 *  - 훈련구분 training_course_details.training_target_name (실험 DB: 137,399행에 11종)
 *  - 정책 keywords youth_policy_details.keywords (실험 DB: 5,250행에 541종)
 *
 * 두 컬럼은 행이 많고 값 종류가 적다. 검색 SQL에서 행마다 정규화하면 그 비용이 검색 시간 대부분을
 * 차지하므로(훈련구분 워커당 약 180ms), 값 종류마다 한 번만 정규화한 결과로 검색 토큰에 맞는 원문 값을
 * 먼저 고르고 검색 SQL은 원문 값을 그대로 비교한다. 맞는 값이 없으면 그 비교 자체를 뺀다.
 *
 * 정규화는 Java가 아니라 DB가 검색과 같은 식(SearchSqlFunctions.stripSql)으로 계산한다.
 * 목록은 검색마다 새로 읽는다 (훈련구분 약 25ms, keywords 약 10ms). 값 종류를 코드에 두거나 캐시하지 않으므로
 * 새 값이 생겨도 그 검색부터 반영된다. 목록·COUNT 질의가 서로 다른 문장인 것과 같은 수준의 시점 차이만 있다.
 * Hibernate 문장이 아닌 JDBC로 조회해 검색 질의 수 계측에 섞이지 않게 한다.
 */
@Component
public class DetailValueNames {

    private static final String TRAINING_TARGETS = distinct("training_target_name", "training_course_details");
    private static final String POLICY_KEYWORDS = distinct("keywords", "youth_policy_details");

    private final JdbcTemplate jdbc;

    public DetailValueNames(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 훈련구분 원문 값 -> 정규화 값 (search_title_strip과 같은 식) */
    public Map<String, String> trainingTargets() {
        return load(TRAINING_TARGETS);
    }

    /** 정책 keywords 원문 값(쉼표 구분 목록 전체) -> 정규화 값 (search_title_strip과 같은 식) */
    public Map<String, String> policyKeywords() {
        return load(POLICY_KEYWORDS);
    }

    private Map<String, String> load(String sql) {
        Map<String, String> normalizedByValue = new LinkedHashMap<>();
        jdbc.query(sql, row -> {
            normalizedByValue.put(row.getString(1), row.getString(2));
        });
        return normalizedByValue;
    }

    private static String distinct(String column, String table) {
        return "SELECT value, " + SearchSqlFunctions.stripSql("value") + " FROM (SELECT DISTINCT " + column
                + " AS value FROM " + table + " WHERE " + column + " IS NOT NULL) d";
    }
}
