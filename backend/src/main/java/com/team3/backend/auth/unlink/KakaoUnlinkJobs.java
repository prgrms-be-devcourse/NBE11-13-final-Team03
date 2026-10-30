package com.team3.backend.auth.unlink;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KakaoUnlinkJobs {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public KakaoUnlinkJobs(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long kakaoId) {
        jdbc.update("""
                INSERT INTO kakao_unlink_tasks(kakao_id,status,attempt_count,next_attempt_at,created_at)
                VALUES (?,'PENDING',0,?,?) ON CONFLICT(kakao_id) DO UPDATE
                SET status='PENDING',attempt_count=0,next_attempt_at=EXCLUDED.next_attempt_at,
                    created_at=EXCLUDED.created_at,completed_at=NULL,last_error=NULL
                """, kakaoId, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }

    /** The identity mutex is held by the caller until the login transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireLoginAllowed(long kakaoId, Instant userInfoRequestedAt) {
        var blocked = jdbc.query("""
                SELECT status,completed_at FROM kakao_unlink_tasks WHERE kakao_id=? FOR UPDATE
                """, (rs, row) -> !"COMPLETED".equals(rs.getString("status"))
                        || rs.getTimestamp("completed_at") == null
                        || !rs.getTimestamp("completed_at").toInstant().isBefore(userInfoRequestedAt), kakaoId);
        if (!blocked.isEmpty() && blocked.getFirst()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("kakao_unlink_pending"),
                    "카카오 연결 해제 처리 중입니다. 잠시 후 다시 로그인해 주세요.");
        }
    }
}
