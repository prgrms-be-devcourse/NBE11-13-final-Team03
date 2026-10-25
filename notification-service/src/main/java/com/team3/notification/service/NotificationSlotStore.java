package com.team3.notification.service;

import com.team3.notification.config.NotificationPushProperties;
import com.team3.notification.config.NotificationSlotProperties;
import com.team3.notification.push.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Slot mutations: user advisory mutex -> bundle row -> delivery rows. No external I/O in transactions. */
@Service
@ConditionalOnProperty(name = "yon.notification.push.slots.enabled", havingValue = "true")
public class NotificationSlotStore {
    private final JdbcTemplate jdbc;
    private final NotificationUserGuard guard;
    private final NotificationSlotProperties slots;
    private final NotificationPushProperties push;
    private final Clock clock;
    public NotificationSlotStore(JdbcTemplate jdbc, NotificationUserGuard guard, NotificationSlotProperties slots,
            NotificationPushProperties push, Clock clock, NotificationSlotMigrationGuard migration) {
        migration.check(); // Before this store/any scheduled worker can be published by Spring.
        this.jdbc = jdbc; this.guard = guard; this.slots = slots; this.push = push; this.clock = clock;
    }
    public record Claim(long id, long bundleId, long userId, UUID token) { }
    public record BundleRef(long id, long userId) { }
    private record Bundle(long id, long userId, LocalDate date, int slot, Instant start, Instant end, String status) {
        boolean open(Instant now) { return status.equals("READY") && !now.isBefore(start) && now.isBefore(end); }
    }
    private record Member(long id, String title, String content, String link, Instant expires, UUID message, UUID correlation) { }
    private static Timestamp ts(Instant time) { return Timestamp.from(time); }

    /** Queue-only expiry CAS; never acquires a user/bundle lock after a queue row lock. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expirePending() {
        return jdbc.update("""
                WITH expired AS (SELECT q.notification_id FROM notification_push_pending q
                  JOIN notifications n ON n.id=q.notification_id WHERE q.status='PENDING'
                    AND LEAST(n.expires_at,COALESCE(n.push_expires_at,n.expires_at))<=?
                  ORDER BY q.notification_id LIMIT ? FOR UPDATE OF q SKIP LOCKED)
                UPDATE notification_push_pending q SET status='SKIPPED',reason='PUSH_EXPIRED'
                FROM expired e WHERE q.notification_id=e.notification_id AND q.status='PENDING'
                """,ts(clock.instant()),Math.min(1000,slots.itemsPerBundle()*slots.usersPerRun()));
    }

    @Transactional(readOnly = true)
    public List<Long> candidates() {
        var now = clock.instant(); var current = PushSlotPolicy.current(now);
        if (current.isEmpty()) return List.of();
        var window = current.orElseThrow();
        return jdbc.queryForList("""
                SELECT DISTINCT q.user_id FROM notification_push_pending q JOIN notifications n ON n.id=q.notification_id
                WHERE q.status='PENDING' AND LEAST(n.expires_at,COALESCE(n.push_expires_at,n.expires_at)) > ?
                  AND NOT EXISTS (SELECT 1 FROM notification_push_bundles b
                    WHERE b.user_id=q.user_id AND b.kst_date=? AND b.slot_no=?)
                ORDER BY q.user_id LIMIT ?
                """, Long.class, ts(now), window.date(), window.number(), slots.usersPerRun());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> build(long userId) {
        guard.lock(userId);
        var now = clock.instant(); var current = PushSlotPolicy.current(now);
        if (current.isEmpty() || guard.isWithdrawn(userId)) return Optional.empty();
        var window = current.orElseThrow();
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notification_push_bundles WHERE user_id=? AND kst_date=? AND slot_no=?)
                """, Boolean.class, userId, window.date(), window.number()))) return Optional.empty();
        if (!consent(userId) || !hasEndpoint(userId)) {
            jdbc.update("UPDATE notification_push_pending SET status='SKIPPED',reason='CHANNEL_DISABLED' WHERE user_id=? AND status='PENDING'", userId);
            return Optional.empty();
        }
        var ids = jdbc.queryForList("""
                SELECT q.notification_id FROM notification_push_pending q JOIN notifications n ON n.id=q.notification_id
                WHERE q.user_id=? AND q.status='PENDING' AND LEAST(n.expires_at,COALESCE(n.push_expires_at,n.expires_at)) > ?
                ORDER BY q.priority,n.created_at,n.id LIMIT ?
                """, Long.class, userId, ts(now), slots.itemsPerBundle());
        if (ids.isEmpty() || !window.contains(clock.instant())) return Optional.empty();
        Long bundleId = jdbc.queryForObject("""
                INSERT INTO notification_push_bundles(user_id,kst_date,slot_no,window_start,window_end,
                  representative_notification_id,policy_version,status,created_at)
                VALUES (?,?,?,?,?,?,?,'READY',?) RETURNING id
                """, Long.class, userId, window.date(), window.number(), ts(window.start()), ts(window.end()),
                ids.getFirst(), PushSlotPolicy.VERSION, ts(now));
        for (Long id : ids) {
            jdbc.update("UPDATE notification_push_pending SET status='BUNDLED',bundle_id=? WHERE notification_id=? AND status='PENDING'", bundleId, id);
        }
        jdbc.update("""
                INSERT INTO notification_bundle_deliveries(bundle_id,endpoint_id,user_id,status,next_retry_at,created_at)
                SELECT ?,id,user_id,'PENDING',?,? FROM notification_endpoints WHERE user_id=? AND active
                ON CONFLICT (bundle_id,endpoint_id) DO NOTHING
                """, bundleId, ts(now), ts(now), userId);
        return Optional.of(bundleId);
    }

    /** Lease claim is not yet a send attempt. Bundle slot/window is checked again under the user lock in prepare. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimNext() {
        var now = clock.instant(); if (PushSlotPolicy.current(now).isEmpty()) return Optional.empty();
        var token = UUID.randomUUID();
        return jdbc.query("""
                WITH candidate AS (
                  SELECT d.id FROM notification_bundle_deliveries d JOIN notification_push_bundles b ON b.id=d.bundle_id
                  WHERE d.status='PENDING' AND d.next_retry_at<=? AND b.status='READY' AND b.window_start<=? AND b.window_end>?
                  ORDER BY d.next_retry_at,d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                )
                UPDATE notification_bundle_deliveries d SET status='CLAIMED',processing_started_at=?,lease_token=?
                FROM candidate c WHERE d.id=c.id RETURNING d.id,d.bundle_id,d.user_id
                """, (rs,row) -> new Claim(rs.getLong(1), rs.getLong(2), rs.getLong(3), token), ts(now),ts(now),ts(now),ts(now),token)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PushMessage> prepare(Claim claim) {
        guard.lock(claim.userId());
        var bundle = lock(claim.bundleId(), claim.userId());
        if (bundle.isEmpty()) return Optional.empty();
        var b = bundle.orElseThrow(); var now = clock.instant();
        var rows = jdbc.query("""
                SELECT e.id,e.fcm_token,e.active FROM notification_bundle_deliveries d
                JOIN notification_endpoints e ON e.id=d.endpoint_id AND e.user_id=d.user_id
                WHERE d.id=? AND d.bundle_id=? AND d.user_id=? AND d.status='CLAIMED' AND d.lease_token=? FOR UPDATE OF d
                """, (rs,row) -> new Endpoint(rs.getLong(1), rs.getString(2), rs.getBoolean(3)),
                claim.id(),claim.bundleId(),claim.userId(),claim.token());
        if (rows.isEmpty()) return Optional.empty();
        if (!b.open(now) || guard.isWithdrawn(claim.userId()) || !consent(claim.userId())) {
            close(b, "WINDOW_OR_USER_DISABLED"); return Optional.empty();
        }
        var endpoint = rows.getFirst();
        if (!endpoint.active()) { skip(claim,"ENDPOINT_INACTIVE"); finish(b); return Optional.empty(); }
        jdbc.update("""
                UPDATE notification_push_pending q SET status='SKIPPED',reason='PUSH_EXPIRED'
                FROM notifications n WHERE q.notification_id=n.id AND q.bundle_id=? AND q.status='BUNDLED'
                  AND LEAST(n.expires_at,COALESCE(n.push_expires_at,n.expires_at))<=?
                """, b.id(),ts(now));
        var members = members(b.id());
        if (members.isEmpty()) { close(b,"PUSH_EXPIRED"); return Optional.empty(); }
        var first = members.getFirst();
        var expiry = members.stream().map(Member::expires).min(Comparator.naturalOrder()).orElseThrow();
        if (b.end().isBefore(expiry)) expiry = b.end();
        // A slow DB transaction must not start a send after the window/earliest member has expired.
        if (!clock.instant().isBefore(expiry)) { skip(claim,"PUSH_EXPIRED"); finish(b); return Optional.empty(); }
        jdbc.update("UPDATE notification_push_bundles SET representative_notification_id=?,first_attempt_at=COALESCE(first_attempt_at,?) WHERE id=?",
                first.id(),ts(now),b.id());
        jdbc.update("""
                INSERT INTO notification_push_slot_usage(user_id,kst_date,slot_no,bundle_id,first_attempt_at)
                VALUES (?,?,?,?,?) ON CONFLICT (user_id,kst_date,slot_no) DO NOTHING
                """, b.userId(),b.date(),b.slot(),b.id(),ts(now));
        if (!Objects.equals(jdbc.queryForObject("SELECT bundle_id FROM notification_push_slot_usage WHERE user_id=? AND kst_date=? AND slot_no=?",
                Long.class,b.userId(),b.date(),b.slot()),b.id())) throw new IllegalStateException("Slot already used by another bundle");
        jdbc.update("UPDATE notification_bundle_deliveries SET status='SENDING',processing_started_at=? WHERE id=? AND lease_token=?",ts(now),claim.id(),claim.token());
        int count = members.size();
        String title = shortText(first.title(),160) + (count > 1 ? " 외 " + (count-1) + "건" : "");
        String content = count > 1 ? "알림함에서 총 " + count + "건의 알림을 확인해 주세요." : shortText(first.content(),500);
        return Optional.of(new PushMessage(claim.id(),first.id(),b.userId(),endpoint.id(),claim.token(),endpoint.token(),
                title,content,count > 1 ? slots.inboxPath() : first.link(),expiry,first.message(),first.correlation(),b.id()));
    }
    private record Endpoint(long id, String token, boolean active) { }
    private static String shortText(String value, int max) { return value.codePoints().limit(max).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString(); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sent(Claim claim, String receipt) {
        guard.lock(claim.userId()); var b = lock(claim.bundleId(),claim.userId()); if (b.isEmpty()) return false;
        boolean changed = jdbc.update("""
                UPDATE notification_bundle_deliveries SET status='SENT',sent_at=?,fcm_message_id=?,processing_started_at=NULL,
                  lease_token=NULL,last_error=NULL WHERE id=? AND bundle_id=? AND status='SENDING' AND lease_token=?
                """,ts(clock.instant()),receipt,claim.id(),claim.bundleId(),claim.token())==1;
        if (changed) finish(b.orElseThrow()); return changed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failed(Claim claim, PushMessage message, PushFailure failure) {
        guard.lock(claim.userId()); var b = lock(claim.bundleId(),claim.userId()); if (b.isEmpty()) return false;
        boolean changed;
        if (failure.kind()==PushFailure.Kind.INVALID_TOKEN) {
            changed=skipSending(claim,"FCM_UNREGISTERED");
            if (changed) jdbc.update("UPDATE notification_endpoints SET active=false,updated_at=? WHERE id=? AND user_id=? AND fcm_token=?",
                    ts(clock.instant()),message.endpointId(),claim.userId(),message.fcmToken());
        } else if (!b.orElseThrow().open(clock.instant()) || "PUSH_EXPIRED".equals(failure.getMessage())) {
            changed=skipSending(claim,"WINDOW_OR_PUSH_EXPIRED");
        } else {
            boolean config=failure.kind()==PushFailure.Kind.CONFIGURATION;
            changed=jdbc.update("""
                    UPDATE notification_bundle_deliveries SET retry_count=retry_count+?,
                      status=CASE WHEN ? OR (? AND retry_count+1<?) THEN 'PENDING' ELSE 'FAILED' END,
                      next_retry_at=?::timestamptz+(? * power(2,least(retry_count,6)))*interval '1 millisecond',
                      processing_started_at=NULL,lease_token=NULL,last_error=?
                    WHERE id=? AND bundle_id=? AND status='SENDING' AND lease_token=?
                    """,config?0:1,config,failure.kind()==PushFailure.Kind.TRANSIENT,push.maxAttempts(),ts(clock.instant()),
                    push.retryDelay().toMillis(),failure.getMessage(),claim.id(),claim.bundleId(),claim.token())==1;
        }
        if (changed) finish(b.orElseThrow()); return changed;
    }

    @Transactional(readOnly = true)
    public List<BundleRef> expiredBundles() {
        return jdbc.query("SELECT id,user_id FROM notification_push_bundles WHERE status='READY' AND window_end<=? ORDER BY window_end,id LIMIT ?",
                (rs,row)->new BundleRef(rs.getLong(1),rs.getLong(2)),ts(clock.instant()),slots.usersPerRun());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(BundleRef ref) {
        guard.lock(ref.userId()); var b=lock(ref.id(),ref.userId());
        if (b.isPresent() && !clock.instant().isBefore(b.orElseThrow().end()) && b.orElseThrow().status().equals("READY")) close(b.orElseThrow(),"SLOT_WINDOW_ENDED");
    }

    /** Lease-only CAS recovery never acquires bundle/user locks after locking a delivery. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStale() {
        return jdbc.update("""
                WITH stale AS (SELECT d.id FROM notification_bundle_deliveries d JOIN notification_push_bundles b ON b.id=d.bundle_id
                  WHERE d.status IN ('CLAIMED','SENDING') AND d.processing_started_at<? AND b.status='READY' AND b.window_end>?
                  ORDER BY d.processing_started_at,d.id LIMIT ? FOR UPDATE OF d SKIP LOCKED)
                UPDATE notification_bundle_deliveries d SET
                  retry_count=d.retry_count+CASE WHEN d.status='SENDING' THEN 1 ELSE 0 END,
                  status=CASE WHEN d.status='CLAIMED' OR d.retry_count+1<? THEN 'PENDING' ELSE 'FAILED' END,
                  next_retry_at=CASE WHEN d.status='CLAIMED' THEN ?::timestamptz
                    ELSE ?::timestamptz+(? * power(2,least(d.retry_count,6)))*interval '1 millisecond' END,
                  processing_started_at=NULL,lease_token=NULL,last_error='DELIVERY_LEASE_EXPIRED'
                FROM stale s WHERE d.id=s.id
                """,ts(clock.instant().minus(push.leaseTimeout())),ts(clock.instant()),push.batchSize(),push.maxAttempts(),
                ts(clock.instant()),ts(clock.instant()),push.retryDelay().toMillis());
    }

    private Optional<Bundle> lock(long id,long userId) {
        return jdbc.query("SELECT * FROM notification_push_bundles WHERE id=? AND user_id=? FOR UPDATE",(rs,row)->
                new Bundle(rs.getLong("id"),rs.getLong("user_id"),rs.getObject("kst_date",LocalDate.class),rs.getInt("slot_no"),
                        rs.getTimestamp("window_start").toInstant(),rs.getTimestamp("window_end").toInstant(),rs.getString("status")),id,userId).stream().findFirst();
    }
    private List<Member> members(long id) {
        return jdbc.query("""
                SELECT n.id,n.title,n.content,n.link,n.message_id,n.correlation_id,
                  LEAST(n.expires_at,COALESCE(n.push_expires_at,n.expires_at)) AS expiry
                FROM notification_push_pending q JOIN notifications n ON n.id=q.notification_id
                WHERE q.bundle_id=? AND q.status='BUNDLED' ORDER BY q.priority,n.created_at,n.id
                """,(rs,row)->new Member(rs.getLong("id"),rs.getString("title"),rs.getString("content"),rs.getString("link"),
                        rs.getTimestamp("expiry").toInstant(),rs.getObject("message_id",UUID.class),rs.getObject("correlation_id",UUID.class)),id);
    }
    private boolean consent(long userId) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COALESCE((SELECT push_enabled FROM notification_settings WHERE user_id=?),false)",Boolean.class,userId)); }
    private boolean hasEndpoint(long userId) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notification_endpoints WHERE user_id=? AND active)",Boolean.class,userId)); }
    private boolean skip(Claim c,String reason) { return jdbc.update("UPDATE notification_bundle_deliveries SET status='SKIPPED',last_error=?,processing_started_at=NULL,lease_token=NULL WHERE id=? AND bundle_id=? AND status='CLAIMED' AND lease_token=?",reason,c.id(),c.bundleId(),c.token())==1; }
    private boolean skipSending(Claim c,String reason) { return jdbc.update("UPDATE notification_bundle_deliveries SET status='SKIPPED',last_error=?,processing_started_at=NULL,lease_token=NULL WHERE id=? AND bundle_id=? AND status='SENDING' AND lease_token=?",reason,c.id(),c.bundleId(),c.token())==1; }
    private void close(Bundle b,String reason) {
        // A valid SENDING lease may already be in FCM. Preserve its token so the acceptance receipt can still commit.
        // PENDING/CLAIMED have not started I/O; abandoned SENDING is terminal only after the lease expires.
        jdbc.update("""
                UPDATE notification_bundle_deliveries SET status='SKIPPED',last_error=?,processing_started_at=NULL,lease_token=NULL
                WHERE bundle_id=? AND (status IN ('PENDING','CLAIMED')
                  OR (status='SENDING' AND processing_started_at<=?))
                """,reason,b.id(),ts(clock.instant().minus(push.leaseTimeout())));
        finish(b);
    }
    private void finish(Bundle b) {
        jdbc.update("""
                UPDATE notification_push_bundles b SET status=CASE
                  WHEN EXISTS(SELECT 1 FROM notification_bundle_deliveries d WHERE d.bundle_id=b.id AND d.status='SENT') THEN 'COMPLETED'
                  WHEN EXISTS(SELECT 1 FROM notification_bundle_deliveries d WHERE d.bundle_id=b.id AND d.status='FAILED') THEN 'FAILED' ELSE 'SKIPPED' END,
                  closed_at=? WHERE b.id=? AND b.status='READY'
                  AND NOT EXISTS(SELECT 1 FROM notification_bundle_deliveries d WHERE d.bundle_id=b.id AND d.status IN ('PENDING','CLAIMED','SENDING'))
                """,ts(clock.instant()),b.id());
    }
}
