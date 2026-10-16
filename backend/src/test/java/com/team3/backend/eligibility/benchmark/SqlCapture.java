package com.team3.backend.eligibility.benchmark;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 벤치마크 전용 SQL 수집기. Hibernate가 준비하는 모든 SQL(지연 로딩 포함)을 그대로 기록한다.
 * EligibilityBottleneckBenchmarkTest에서만 hibernate.session_factory.statement_inspector로 등록한다.
 */
public class SqlCapture implements StatementInspector {

    private static volatile boolean enabled;
    private static final List<String> SQLS = Collections.synchronizedList(new ArrayList<>());

    static void start() {
        SQLS.clear();
        enabled = true;
    }

    static List<String> stop() {
        enabled = false;
        synchronized (SQLS) {
            return List.copyOf(SQLS);
        }
    }

    @Override
    public String inspect(String sql) {
        if (enabled) {
            SQLS.add(sql);
        }
        return sql;
    }
}
