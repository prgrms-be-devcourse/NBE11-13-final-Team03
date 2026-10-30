package com.team3.backend.auth.unlink;

import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class KakaoUnlinkProcessor {
    private static final Logger log = LoggerFactory.getLogger(KakaoUnlinkProcessor.class);
    private final JdbcTemplate jdbc;
    private final KakaoUnlinkClient client;
    private final KakaoUnlinkProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public KakaoUnlinkProcessor(JdbcTemplate jdbc, KakaoUnlinkClient client, KakaoUnlinkProperties properties, Clock clock,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.client = client; this.properties = properties; this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Persist PROCESSING before HTTP. Never auto-reclaim an ambiguous in-flight request: a late
     * unlink must not run after re-registration. Crashed PROCESSING jobs need operator reconciliation. */
    public boolean processNext() {
        Job job = transaction.execute(tx -> {
            var jobs = jdbc.query("""
                SELECT kakao_id,attempt_count FROM kakao_unlink_tasks
                WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,kakao_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Job(rs.getLong(1), rs.getInt(2)), Timestamp.from(clock.instant()));
            if (jobs.isEmpty()) return null;
            var claimed = jobs.getFirst();
            jdbc.update("UPDATE kakao_unlink_tasks SET status='PROCESSING',attempt_count=attempt_count+1 WHERE kakao_id=?", claimed.kakaoId());
            return claimed;
        });
        if (job == null) return false;
        try {
            client.unlink(job.kakaoId());
        } catch (KakaoUnlinkClient.Failure failure) {
            int attempts = job.attempts() + 1;
            boolean retry = failure.retryable() && attempts < properties.maxAttempts();
            // Timeouts are ambiguous. Block re-registration until a confirmed receipt or operator reconciliation.
            transaction.executeWithoutResult(tx -> jdbc.update("""
                    UPDATE kakao_unlink_tasks SET status=?,next_attempt_at=?,last_error=? WHERE kakao_id=? AND status='PROCESSING'
                    """, retry ? "PENDING" : "FAILED",
                    Timestamp.from(clock.instant().plus(properties.retryDelay().multipliedBy(1L << Math.min(job.attempts(), 6)))),
                    failure.getMessage(), job.kakaoId()));
            log.warn("Kakao unlink failed: attempts={}, retry={}, code={}", attempts, retry, failure.getMessage());
            return true;
        }
        transaction.executeWithoutResult(tx -> jdbc.update("""
                UPDATE kakao_unlink_tasks SET status='COMPLETED',
                    completed_at=?,last_error=NULL WHERE kakao_id=? AND status='PROCESSING'
                """, Timestamp.from(clock.instant()), job.kakaoId()));
        log.info("Kakao unlink completed");
        return true;
    }
    private record Job(long kakaoId, int attempts) { }
}
