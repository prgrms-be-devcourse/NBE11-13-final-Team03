package com.team3.backend.outbox.service;

import com.team3.backend.outbox.config.NotificationPublisherProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

public class OutboxPublicationStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final NotificationPublisherProperties properties;

    public OutboxPublicationStore(JdbcTemplate jdbc, Clock clock, NotificationPublisherProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OutboxPublication> claimNext() {
        var now = Timestamp.from(clock.instant());
        UUID token = UUID.randomUUID();
        var publications = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM outbox_events
                    WHERE status = 'PENDING' AND next_retry_at <= ? AND event_type = 'NOTIFICATION_REQUESTED'
                    ORDER BY next_retry_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_events o SET status = 'PUBLISHING', processing_started_at = ?, lease_token = ?
                FROM candidate c WHERE o.id = c.id
                RETURNING o.id, o.message_id, o.payload::text
                """, (row, index) -> new OutboxPublication(row.getLong("id"),
                row.getObject("message_id", UUID.class), token, row.getString("payload")), now, now, token);
        return publications.stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean published(OutboxPublication publication) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'PUBLISHED', published_at = ?, processing_started_at = NULL,
                    lease_token = NULL, last_error = NULL
                WHERE id = ? AND status = 'PUBLISHING' AND lease_token = ?
                """, Timestamp.from(clock.instant()), publication.id(), publication.leaseToken()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failed(OutboxPublication publication, String safeCode, boolean retryable) {
        return jdbc.update("""
                UPDATE outbox_events SET retry_count = retry_count + 1,
                    status = CASE WHEN ? AND retry_count + 1 < ? THEN 'PENDING' ELSE 'FAILED' END,
                    next_retry_at = ?::timestamptz + (? * power(2, least(retry_count, 6))) * interval '1 millisecond',
                    processing_started_at = NULL, lease_token = NULL, last_error = ?
                WHERE id = ? AND status = 'PUBLISHING' AND lease_token = ?
                """, retryable, properties.maxAttempts(), Timestamp.from(clock.instant()), properties.retryDelay().toMillis(),
                safeCode, publication.id(), publication.leaseToken()) == 1;
    }

    /** Configuration failures return the message to PENDING without spending an attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deferred(OutboxPublication publication, String safeCode) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'PENDING',
                    next_retry_at = ?::timestamptz + ? * interval '1 millisecond',
                    processing_started_at = NULL, lease_token = NULL, last_error = ?
                WHERE id = ? AND status = 'PUBLISHING' AND lease_token = ?
                """, Timestamp.from(clock.instant()), properties.retryDelay().toMillis(), safeCode,
                publication.id(), publication.leaseToken()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStale() {
        return jdbc.update("""
                WITH stale AS (
                    SELECT id FROM outbox_events WHERE status = 'PUBLISHING'
                        AND event_type = 'NOTIFICATION_REQUESTED' AND processing_started_at < ?
                    ORDER BY processing_started_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_events o SET retry_count = o.retry_count + 1,
                    status = CASE WHEN o.retry_count + 1 < ? THEN 'PENDING' ELSE 'FAILED' END,
                    next_retry_at = ?::timestamptz + (? * power(2, least(o.retry_count, 6))) * interval '1 millisecond',
                    processing_started_at = NULL, lease_token = NULL,
                    last_error = 'PUBLISH_LEASE_EXPIRED'
                FROM stale s WHERE o.id = s.id
                """, Timestamp.from(clock.instant().minus(properties.leaseTimeout())), properties.batchSize(),
                properties.maxAttempts(), Timestamp.from(clock.instant()), properties.retryDelay().toMillis());
    }
}
