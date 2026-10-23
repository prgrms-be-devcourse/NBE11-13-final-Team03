package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.tracking.config.EventFanoutProperties;
import com.team3.backend.tracking.domain.model.EventFanoutExecution;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventFanoutBatch {
    private final OpportunityEventRepository events;
    private final OpportunityRepository opportunities;
    private final ApplicationWindowRepository windows;
    private final UserOpportunityPreferenceRepository preferences;
    private final OutboxEventRepository outboxes;
    private final DateNotificationMessageFactory messages;
    private final EventFanoutProperties properties;
    private final Clock clock;

    /** Cursor, Outbox inserts and heartbeat/finish commit or roll back together. */
    @Transactional
    public boolean process(EventFanoutExecution execution) {
        var event = events.lockForFanout(execution.eventId()).orElseThrow();
        if (!event.ownedBy(execution.leaseToken())) throw new IllegalStateException("Event fanout ownership lost");
        if (!messages.supports(event.getEventType())) {
            event.fail(clock.instant(), "UNSUPPORTED_EVENT_TYPE");
            return true;
        }
        if (!event.isNotificationEligible()) {
            event.skipFanout(clock.instant(), "EVENT_SUPPRESSED");
            return true;
        }
        var current = opportunities.lockForNotification(event.getOpportunity().getId());
        if (current.isEmpty()) {
            event.skipFanout(clock.instant(), "POLICY_UNAVAILABLE");
            return true;
        }
        var periods = windows.findByVersionId(current.orElseThrow().getCurrentVersion().getId()).stream()
                .map(w -> new ApplicationPeriod(w.getPeriodType(), w.getStartDate(), w.getEndDate(),
                        w.getValidationStatus(), w.getPeriodText())).toList();
        var prepared = messages.prepare(event, current.orElseThrow(), periods, clock.instant());
        if (prepared.isEmpty()) {
            event.skipFanout(clock.instant(), "POLICY_NOT_RELEVANT_OR_EXPIRED");
            return true;
        }
        var userIds = preferences.findSavedUserIdsAfter(event.getOpportunity().getId(), event.getLastUserId(),
                PageRequest.of(0, properties.batchSize()));
        if (userIds.isEmpty()) {
            event.complete(clock.instant());
            return true;
        }
        var saved = preferences.lockSavedForNotification(event.getOpportunity().getId(), userIds);
        // One lookup per page; the UNIQUE(dedup_key) constraint remains the actual guard.
        var existing = saved.isEmpty() ? java.util.Set.<String>of() : outboxes.findExistingDedupKeys(saved.stream()
                .map(p -> OutboxEvent.notificationDedupKey(event.getEventId(), p.getUser().getId())).toList());
        for (int i = 0; i < saved.size(); i++) {
            var now = clock.instant();
            if (!now.isBefore(prepared.orElseThrow().expiresAt())) {
                // Expired messages are not sent; record where and how many were left instead of moving on silently.
                outboxes.flush();
                log.info("Event fanout expired during page: eventId={}, lastUserId={}, skippedUsersInPage={}",
                        event.getEventId(), event.getLastUserId(), saved.size() - i);
                event.skipFanout(now, "EXPIRED_DURING_FANOUT");
                return true;
            }
            Long userId = saved.get(i).getUser().getId();
            String key = OutboxEvent.notificationDedupKey(event.getEventId(), userId);
            if (!existing.contains(key)) {
                outboxes.save(messages.create(event, userId, prepared.orElseThrow(), now));
            }
        }
        outboxes.flush();
        event.advanceFanout(userIds.getLast(), clock.instant());
        return false;
    }
}
