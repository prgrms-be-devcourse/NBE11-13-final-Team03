package com.team3.notification.service;

import com.team3.notification.api.NotificationUserDtos.*;
import com.team3.notification.domain.repository.NotificationRepository;
import com.team3.notification.domain.repository.NotificationSettingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;

@Service
@Transactional(readOnly = true)
public class NotificationUserService {
    private final NotificationRepository notifications;
    private final NotificationSettingRepository settings;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final NotificationUserGuard guard;

    public NotificationUserService(NotificationRepository notifications, NotificationSettingRepository settings,
                                   JdbcTemplate jdbc, Clock clock, NotificationUserGuard guard) {
        this.notifications = notifications;
        this.settings = settings;
        this.jdbc = jdbc;
        this.clock = clock;
        this.guard = guard;
    }

    public Page list(long userId, Long beforeId, int size) {
        guard.requireActive(userId);
        if (size < 1 || size > 100 || (beforeId != null && beforeId < 1)) {
            throw new NotificationUserApiException(400, "INVALID_NOTIFICATION_REQUEST");
        }
        var rows = notifications.page(userId, beforeId == null ? Long.MAX_VALUE : beforeId, PageRequest.of(0, size + 1));
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(Item::from).toList();
        return new Page(items, more ? items.getLast().id() : null, more);
    }

    public UnreadCount unreadCount(long userId) {
        guard.requireActive(userId);
        return new UnreadCount(true, notifications.countByUserIdAndReadAtIsNullAndInAppVisibleTrue(userId));
    }

    @Transactional
    public void read(long userId, long id) {
        guard.lockActive(userId);
        requireOwned(userId, id);
        notifications.markRead(userId, id, clock.instant());
    }

    @Transactional
    public void readAll(long userId) { guard.lockActive(userId); notifications.markAllRead(userId, clock.instant()); }

    @Transactional
    public void click(long userId, long id) {
        guard.lockActive(userId);
        requireOwned(userId, id);
        notifications.markClicked(userId, id, clock.instant());
    }

    private void requireOwned(long userId, long id) {
        if (!notifications.existsByIdAndUserIdAndInAppVisibleTrue(id, userId)) {
            throw new NotificationUserApiException(404, "NOTIFICATION_NOT_FOUND");
        }
    }

    public Settings settings(long userId) {
        guard.requireActive(userId);
        return settings.findById(userId).map(s -> new Settings(s.isInAppEnabled(), s.isPushEnabled(),
                s.getPushConsentAt(), s.getUpdatedAt())).orElseGet(() -> new Settings(true, false, null, null));
    }

    @Transactional
    public Settings updateSettings(long userId, SettingsRequest request) {
        guard.lockActive(userId);
        var now = Timestamp.from(clock.instant());
        // A single PostgreSQL UPSERT handles concurrent first writes without a failed JPA transaction.
        return jdbc.queryForObject("""
                INSERT INTO notification_settings AS settings_row (user_id, in_app_enabled, push_enabled, push_consent_at, updated_at)
                VALUES (?, ?, ?, CASE WHEN ? THEN ?::timestamptz ELSE NULL END, ?)
                ON CONFLICT (user_id) DO UPDATE SET in_app_enabled = EXCLUDED.in_app_enabled,
                  push_enabled = EXCLUDED.push_enabled,
                  push_consent_at = CASE WHEN EXCLUDED.push_enabled
                    THEN COALESCE(settings_row.push_consent_at, EXCLUDED.push_consent_at) ELSE NULL END,
                  updated_at = EXCLUDED.updated_at
                RETURNING in_app_enabled, push_enabled, push_consent_at, updated_at
                """, (rs, row) -> new Settings(rs.getBoolean(1), rs.getBoolean(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()),
                userId, request.inAppEnabled(), request.pushEnabled(), request.pushEnabled(), now, now);
    }

    @Transactional
    public Endpoint registerEndpoint(long userId, EndpointRequest request) {
        guard.lockActive(userId);
        var now = Timestamp.from(clock.instant());
        // Re-registration moves the token to the current session; a request without a session keeps the known one.
        var result = jdbc.query("""
                INSERT INTO notification_endpoints AS endpoint_row (user_id, fcm_token, session_id, active, created_at, updated_at)
                VALUES (?, ?, ?, true, ?, ?)
                ON CONFLICT (fcm_token) DO UPDATE SET active = true,
                  session_id = COALESCE(EXCLUDED.session_id, endpoint_row.session_id), updated_at = EXCLUDED.updated_at
                WHERE endpoint_row.user_id = EXCLUDED.user_id
                RETURNING id, active
                """, (rs, row) -> new Endpoint(rs.getLong(1), rs.getBoolean(2)),
                userId, request.fcmToken(), request.sessionId(), now, now);
        if (result.isEmpty()) throw new NotificationUserApiException(409, "NOTIFICATION_ENDPOINT_CONFLICT");
        return result.getFirst();
    }

    /** Logout path (USER_SESSION_ENDED): no Tombstone check, since a withdrawn user's rows are already gone. */
    @Transactional
    public int deactivateSessionEndpoints(long userId, String sessionId) {
        return jdbc.update("""
                UPDATE notification_endpoints SET active = false, updated_at = ?
                WHERE user_id = ? AND session_id = ? AND active
                """, Timestamp.from(clock.instant()), userId, sessionId);
    }

    @Transactional
    public void deactivateEndpoint(long userId, long id) {
        guard.lockActive(userId);
        int changed = jdbc.update("UPDATE notification_endpoints SET active = false, updated_at = ? WHERE id = ? AND user_id = ?",
                Timestamp.from(clock.instant()), id, userId);
        if (changed == 0) throw new NotificationUserApiException(404, "NOTIFICATION_ENDPOINT_NOT_FOUND");
    }
}
