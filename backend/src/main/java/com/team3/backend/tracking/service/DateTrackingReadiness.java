package com.team3.backend.tracking.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

/**
 * 당일 청년정책 수집·정제가 끝났는지 data-service 소유 테이블(sync_history, raw_normalization_request,
 * raw_external_record, external_source)을 공유 DB에서 읽기만 해서 판단한다.
 * 수집 영역의 엔티티·Repository를 쓰지 않고 테이블 컬럼을 계약으로 삼는다. 수집 쪽 컬럼·상태 값이 바뀌면 이 클래스만 고친다.
 * DB를 서비스별로 분리하면 이 조회는 data-service API(또는 완료 이벤트)로 바꾼다.
 */
@Slf4j
@Service
public class DateTrackingReadiness {

    private static final String SOURCE = "YOUTH_POLICY";

    /**
     * 1. 당일 시작한 최신 수집 이력이 SUCCESS다.
     * 2. PENDING/RUNNING 수집 이력이 없다.
     * 3. 당일 종료 전까지 등록된 정제 요청 중 PENDING이 없다. FAILED는 종료 상태라 기다리지 않는다.
     */
    private static final String READY = """
            SELECT coalesce((SELECT h.status = 'SUCCESS'
                             FROM sync_history h
                             JOIN external_source s ON s.id = h.source_id
                             WHERE s.source_code = :source AND h.started_at >= :from AND h.started_at < :until
                             ORDER BY h.started_at DESC, h.id DESC
                             LIMIT 1), false)
               AND NOT EXISTS (SELECT 1
                               FROM sync_history h
                               JOIN external_source s ON s.id = h.source_id
                               WHERE s.source_code = :source AND h.status IN ('PENDING', 'RUNNING'))
               AND NOT EXISTS (SELECT 1
                               FROM raw_normalization_request q
                               JOIN raw_external_record r ON r.id = q.raw_record_id
                               JOIN external_source s ON s.id = r.source_id
                               WHERE s.source_code = :source AND q.created_at < :until AND q.status = 'PENDING')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DateTrackingReadiness(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** 조회에 실패하면(예: data-service가 아직 테이블을 만들지 않음) 준비되지 않은 것으로 보고 마지노선을 기다린다. */
    public boolean ready(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime until = date.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
        var params = new MapSqlParameterSource()
                .addValue("source", SOURCE)
                .addValue("from", from)
                .addValue("until", until);
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(READY, params, Boolean.class));
        } catch (DataAccessException exception) {
            log.warn("Date tracking readiness check failed; waiting for the cutoff: date={}", date, exception);
            return false;
        }
    }
}
