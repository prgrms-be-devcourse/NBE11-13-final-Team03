package com.team3.backend.auth.unlink;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class KakaoIdentityGuard {
    private final JdbcTemplate jdbc;
    public KakaoIdentityGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Shared by login and withdrawal, before User/task locks. Primary DB transaction is mandatory. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long kakaoId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, "kakao-unlink:" + kakaoId);
    }
}
