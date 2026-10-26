package com.team3.dataservice.opportunity.ingest;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 수집 영역 테이블(raw_normalization_request, raw_external_record, external_source)과 정책 엔진의 raw_ingest_states를 다루는 SQL.
 * 수집 영역의 Repository·엔티티를 쓰지 않고 테이블 컬럼을 계약으로 삼는다. 수집 쪽 컬럼·상태 값이 바뀌면 이 클래스만 고친다.
 * 정책 엔진이 수집 테이블에 쓰는 것은 정제 요청의 status 변경뿐이다.
 */
@Repository
public class RawRecordReader {

    /** raw_normalization_request.status (수집 영역 RecordProcessStatus와 같은 값) */
    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private static final String CLAIM_PENDING = """
            SELECT q.id AS request_id, r.id AS raw_record_id, r.source_id, s.source_code, r.external_id,
                   r.payload_hash, r.payload::text AS payload, r.last_seen_at,
                   r.id = (SELECT max(x.id) FROM raw_external_record x
                           WHERE x.source_id = r.source_id AND x.external_id = r.external_id) AS latest,
                   (st.payload_hash IS NOT NULL AND st.payload_hash = r.payload_hash) AS already_processed
            FROM raw_normalization_request q
            JOIN raw_external_record r ON r.id = q.raw_record_id
            JOIN external_source s ON s.id = r.source_id
            LEFT JOIN raw_ingest_states st ON st.source_id = r.source_id AND st.raw_external_id = r.external_id
            WHERE q.status = :pending
            ORDER BY q.id
            LIMIT :limit
            FOR UPDATE OF q SKIP LOCKED
            """;

    /**
     * 원본을 id 순으로 읽으며 "같은 (출처, 외부 ID)에 더 새 원본이 없다"(최신)와 "처리한 해시와 다르다"를 행마다 확인하고 LIMIT건을 찾으면 멈춘다.
     * PK 범위 조회 + 인덱스(idx_raw_source_external_id) 반조인이라 대조 한 번이 원본 수에 선형이다.
     * (이전 방식: 묶음마다 DISTINCT ON으로 전체 원본을 다시 훑어 원본 수의 제곱으로 느려졌음 — ingest 벤치마크 2026-10-20)
     */
    private static final String FIND_OUT_OF_SYNC = """
            SELECT NULL::bigint AS request_id, r.id AS raw_record_id, r.source_id, s.source_code, r.external_id,
                   r.payload_hash, r.payload::text AS payload, r.last_seen_at,
                   true AS latest, false AS already_processed
            FROM raw_external_record r
            JOIN external_source s ON s.id = r.source_id
            LEFT JOIN raw_ingest_states st ON st.source_id = r.source_id AND st.raw_external_id = r.external_id
            WHERE r.id > :afterId
              AND NOT EXISTS (SELECT 1 FROM raw_external_record n
                              WHERE n.source_id = r.source_id AND n.external_id = r.external_id AND n.id > r.id)
              AND st.payload_hash IS DISTINCT FROM r.payload_hash
            ORDER BY r.id
            LIMIT :limit
            """;

    private static final String MARK_STATUS = """
            UPDATE raw_normalization_request SET status = :status WHERE id IN (:ids)
            """;

    /** 더 새 원본을 이미 기록했으면 덮어쓰지 않는다 */
    private static final String UPSERT_STATE = """
            INSERT INTO raw_ingest_states (source_id, raw_external_id, raw_record_id, payload_hash, outcome, processed_at)
            VALUES (:sourceId, :externalId, :rawRecordId, :payloadHash, :outcome, :processedAt)
            ON CONFLICT (source_id, raw_external_id) DO UPDATE
            SET raw_record_id = EXCLUDED.raw_record_id, payload_hash = EXCLUDED.payload_hash,
                outcome = EXCLUDED.outcome, processed_at = EXCLUDED.processed_at
            WHERE EXCLUDED.raw_record_id >= raw_ingest_states.raw_record_id
            """;

    private static final RowMapper<RawRecordRow> ROW = (rs, i) -> new RawRecordRow(
            rs.getObject("request_id", Long.class),
            rs.getLong("raw_record_id"),
            rs.getLong("source_id"),
            rs.getString("source_code"),
            rs.getString("external_id"),
            rs.getString("payload_hash"),
            rs.getString("payload"),
            rs.getObject("last_seen_at", OffsetDateTime.class),
            rs.getBoolean("latest"),
            rs.getBoolean("already_processed"));

    private final NamedParameterJdbcTemplate jdbc;

    public RawRecordReader(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** PENDING 정제 요청을 id 순으로 선점한다. 호출 트랜잭션이 끝날 때까지 다른 처리기는 이 요청을 건너뛴다 */
    public List<RawRecordRow> claimPending(int limit) {
        return jdbc.query(CLAIM_PENDING, new MapSqlParameterSource()
                .addValue("pending", PENDING)
                .addValue("limit", limit), ROW);
    }

    /** (출처, 외부 ID)별 최신 원본 중 아직 그 해시를 처리하지 않은 것. afterId보다 큰 원본 ID만 (대조 한 번 안에서 다시 시도하지 않도록) */
    public List<RawRecordRow> findOutOfSync(long afterId, int limit) {
        return jdbc.query(FIND_OUT_OF_SYNC, new MapSqlParameterSource()
                .addValue("afterId", afterId)
                .addValue("limit", limit), ROW);
    }

    /**
     * 현재 트랜잭션만 커밋 때 WAL 디스크 기록(fsync)을 기다리지 않게 한다 (SET LOCAL, 트랜잭션이 끝나면 원래대로).
     * DB가 비정상 종료되면 마지막 수백 ms의 커밋이 사라질 수 있지만, 정규화 결과와 처리 상태(raw_ingest_states)가 같은 트랜잭션이라
     * 함께 사라지고 원본은 남으므로 다음 대조가 다시 정규화한다. 요청 상태 변경이 사라지면 PENDING으로 남아 다시 처리된다 (이미 처리한 해시는 건너뜀).
     */
    public void relaxCommitDurability() {
        jdbc.getJdbcTemplate().execute("SET LOCAL synchronous_commit = off");
    }

    public void markStatus(Collection<Long> requestIds, String status) {
        if (requestIds.isEmpty()) {
            return;
        }
        jdbc.update(MARK_STATUS, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("ids", requestIds));
    }

    public void saveState(RawRecordRow row, String outcome, OffsetDateTime processedAt) {
        jdbc.update(UPSERT_STATE, new MapSqlParameterSource()
                .addValue("sourceId", row.sourceId())
                .addValue("externalId", row.externalId())
                .addValue("rawRecordId", row.rawRecordId())
                .addValue("payloadHash", row.payloadHash())
                .addValue("outcome", outcome)
                .addValue("processedAt", processedAt));
    }
}
