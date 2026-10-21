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
 * 같은 사용자를 동시에 저장할 때 잠금 순서가 같도록 opportunity_id 순으로 넣는다.
 * Hibernate 네이티브 쿼리로 실행해 같은 트랜잭션·같은 SQL 통계에 잡힌다. 영속성 컨텍스트의 EligibilityResult는 갱신하지 않는다.
 */
public class EligibilityResultUpsertRepositoryImpl implements EligibilityResultUpsertRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SQL = """
            INSERT INTO eligibility_results AS t (user_id, opportunity_id, opportunity_version_id, profile_version,
                rule_version, eligibility_status, reason, condition_results, valid_until, evaluated_at)
            SELECT :userId, r.opportunity_id, r.opportunity_version_id, :profileVersion,
                :ruleVersion, r.eligibility_status, r.reason, r.condition_results, r.valid_until, :evaluatedAt
            FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS r(opportunity_id bigint, opportunity_version_id bigint,
                eligibility_status varchar, reason varchar, condition_results jsonb, valid_until date)
            ORDER BY r.opportunity_id
            ON CONFLICT (user_id, opportunity_id) DO UPDATE SET
                opportunity_version_id = EXCLUDED.opportunity_version_id,
                profile_version = EXCLUDED.profile_version,
                rule_version = EXCLUDED.rule_version,
                eligibility_status = EXCLUDED.eligibility_status,
                reason = EXCLUDED.reason,
                condition_results = EXCLUDED.condition_results,
                valid_until = EXCLUDED.valid_until,
                evaluated_at = EXCLUDED.evaluated_at
            WHERE EXCLUDED.profile_version >= t.profile_version
              AND EXCLUDED.opportunity_version_id >= t.opportunity_version_id
              AND (t.opportunity_version_id, t.profile_version, t.rule_version, t.eligibility_status, t.reason,
                   t.condition_results, t.valid_until)
                  IS DISTINCT FROM
                  (EXCLUDED.opportunity_version_id, EXCLUDED.profile_version, EXCLUDED.rule_version,
                   EXCLUDED.eligibility_status, EXCLUDED.reason, EXCLUDED.condition_results, EXCLUDED.valid_until)
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
                                       WHERE schemaname = current_schema() AND tablename = 'eligibility_results')
                        """)
                .getSingleResult();
        return !Boolean.TRUE.equals(exists);
    }

    @Override
    public void analyze() {
        em.createNativeQuery("ANALYZE eligibility_results").executeUpdate();
    }

    private static String toJson(List<UpsertRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (UpsertRow row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("opportunity_id", row.opportunityId());
            m.put("opportunity_version_id", row.opportunityVersionId());
            m.put("eligibility_status", row.status().name());
            m.put("reason", row.reason());
            m.put("condition_results", row.conditionResults());
            m.put("valid_until", row.validUntil() == null ? null : row.validUntil().toString());
            out.add(m);
        }
        return JSON.writeValueAsString(out);
    }
}
