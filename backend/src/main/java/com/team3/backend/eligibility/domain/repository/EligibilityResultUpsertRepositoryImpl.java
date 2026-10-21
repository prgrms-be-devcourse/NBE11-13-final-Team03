package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL INSERT … ON CONFLICT DO UPDATE로 묶음을 SQL 1개에 저장한다.
 *
 * 행 데이터는 JSON 배열 하나로 넘기고 jsonb_to_recordset으로 펼친다. 묶음 크기와 상관없이 바인딩 파라미터가 5개라
 * PostgreSQL 파라미터 상한(65,535)에 걸리지 않는다.
 * 같은 사용자를 동시에 저장할 때 잠금 순서가 같도록 condition_set_id 순으로 넣는다.
 * Hibernate 네이티브 쿼리로 실행해 같은 트랜잭션·같은 SQL 통계에 잡힌다. 영속성 컨텍스트의 EligibilityResult는 갱신하지 않는다.
 */
public class EligibilityResultUpsertRepositoryImpl implements EligibilityResultUpsertRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SQL = """
            INSERT INTO eligibility_set_results AS t (user_id, condition_set_id, profile_version,
                rule_version, eligibility_status, reason, condition_results, matched_count, not_matched_count,
                user_info_missing_count, check_required_count, data_unclear_count, valid_until, evaluated_at)
            SELECT :userId, r.condition_set_id, :profileVersion,
                :ruleVersion, r.eligibility_status, r.reason, r.condition_results, r.matched_count, r.not_matched_count,
                r.user_info_missing_count, r.check_required_count, r.data_unclear_count, r.valid_until, :evaluatedAt
            FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS r(condition_set_id bigint,
                eligibility_status varchar, reason varchar, condition_results jsonb, matched_count int,
                not_matched_count int, user_info_missing_count int, check_required_count int, data_unclear_count int,
                valid_until date)
            ORDER BY r.condition_set_id
            ON CONFLICT (user_id, condition_set_id) DO UPDATE SET
                profile_version = EXCLUDED.profile_version,
                rule_version = EXCLUDED.rule_version,
                eligibility_status = EXCLUDED.eligibility_status,
                reason = EXCLUDED.reason,
                condition_results = EXCLUDED.condition_results,
                matched_count = EXCLUDED.matched_count,
                not_matched_count = EXCLUDED.not_matched_count,
                user_info_missing_count = EXCLUDED.user_info_missing_count,
                check_required_count = EXCLUDED.check_required_count,
                data_unclear_count = EXCLUDED.data_unclear_count,
                valid_until = EXCLUDED.valid_until,
                evaluated_at = EXCLUDED.evaluated_at
            WHERE EXCLUDED.profile_version >= t.profile_version
              AND (t.profile_version, t.rule_version, t.eligibility_status, t.reason, t.condition_results, t.valid_until)
                  IS DISTINCT FROM
                  (EXCLUDED.profile_version, EXCLUDED.rule_version, EXCLUDED.eligibility_status, EXCLUDED.reason,
                   EXCLUDED.condition_results, EXCLUDED.valid_until)
            """;

    @PersistenceContext
    private EntityManager em;

    @Override
    public int upsert(Long userId, Long profileVersion, String ruleVersion, LocalDateTime evaluatedAt,
                      List<UpsertRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        return em.createNativeQuery(SQL)
                .unwrap(NativeQuery.class)
                .addSynchronizedEntityClass(EligibilityResult.class)
                .setParameter("userId", userId)
                .setParameter("profileVersion", profileVersion)
                .setParameter("ruleVersion", ruleVersion)
                .setParameter("evaluatedAt", evaluatedAt)
                .setParameter("rows", toJson(rows))
                .executeUpdate();
    }

    @Override
    public boolean lacksStatistics() {
        Object exists = em.createNativeQuery("""
                        SELECT EXISTS (SELECT 1 FROM pg_stats
                                       WHERE schemaname = current_schema() AND tablename = 'eligibility_set_results')
                        """)
                .getSingleResult();
        return !Boolean.TRUE.equals(exists);
    }

    @Override
    public void analyze() {
        em.createNativeQuery("ANALYZE eligibility_set_results").executeUpdate();
    }

    private static String toJson(List<UpsertRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (UpsertRow row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("condition_set_id", row.conditionSetId());
            m.put("eligibility_status", row.status().name());
            m.put("reason", row.reason());
            m.put("condition_results", row.conditionResults());
            m.put("matched_count", count(row, "MATCHED"));
            m.put("not_matched_count", count(row, "NOT_MATCHED"));
            m.put("user_info_missing_count", count(row, "USER_INFO_MISSING"));
            m.put("check_required_count", count(row, "CHECK_REQUIRED"));
            m.put("data_unclear_count", count(row, "DATA_UNCLEAR"));
            m.put("valid_until", row.validUntil() == null ? null : row.validUntil().toString());
            out.add(m);
        }
        return JSON.writeValueAsString(out);
    }

    /** 조건별 결과 중 result가 같은 수 (ConditionMatchResult 이름) */
    static int count(UpsertRow row, String result) {
        return (int) row.conditionResults().stream().filter(c -> result.equals(c.get("result"))).count();
    }
}
