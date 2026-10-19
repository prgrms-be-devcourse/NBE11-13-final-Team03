package com.team3.backend.eligibility.benchmark;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 저장 구간 계측용 SQL 종류별 카운터 (Hibernate가 준비하는 SQL만, JdbcTemplate SQL은 제외).
 * EligibilityResultStoreProfilingTest에서만 hibernate.session_factory.statement_inspector로 등록한다.
 * 문자열 앞부분만 보고 숫자를 올리므로 오버헤드가 작다. 종류별 첫 SQL 하나만 표본으로 남긴다.
 */
public class StoreSqlCounter implements StatementInspector {

    static final AtomicLong SELECT = new AtomicLong();
    static final AtomicLong INSERT = new AtomicLong();
    static final AtomicLong UPDATE = new AtomicLong();
    static final AtomicLong DELETE = new AtomicLong();
    static final AtomicLong OTHER = new AtomicLong();
    static volatile String firstInsert;
    static volatile String firstUpdate;

    record Snapshot(long select, long insert, long update, long delete, long other) {
        Snapshot minus(Snapshot o) {
            return new Snapshot(select - o.select, insert - o.insert, update - o.update, delete - o.delete, other - o.other);
        }

        long total() {
            return select + insert + update + delete + other;
        }

        Snapshot plus(Snapshot o) {
            return new Snapshot(select + o.select, insert + o.insert, update + o.update, delete + o.delete, other + o.other);
        }

        static final Snapshot ZERO = new Snapshot(0, 0, 0, 0, 0);
    }

    static Snapshot now() {
        return new Snapshot(SELECT.get(), INSERT.get(), UPDATE.get(), DELETE.get(), OTHER.get());
    }

    @Override
    public String inspect(String sql) {
        String head = sql.stripLeading();
        head = head.substring(0, Math.min(6, head.length())).toLowerCase(Locale.ROOT);
        switch (head) {
            case "select" -> SELECT.incrementAndGet();
            case "insert" -> {
                INSERT.incrementAndGet();
                if (firstInsert == null) {
                    firstInsert = sql;
                }
            }
            case "update" -> {
                UPDATE.incrementAndGet();
                if (firstUpdate == null) {
                    firstUpdate = sql;
                }
            }
            case "delete" -> DELETE.incrementAndGet();
            default -> OTHER.incrementAndGet();
        }
        return sql;
    }
}
