package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.tracking.config.EventFanoutProperties;
import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import com.team3.backend.tracking.domain.model.EventFanoutExecution;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EventFanoutBatchTest {
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-23T15:00:00Z");

    private final OpportunityEventRepository events = mock(OpportunityEventRepository.class);
    private final OpportunityRepository opportunities = mock(OpportunityRepository.class);
    private final ApplicationWindowRepository windows = mock(ApplicationWindowRepository.class);
    private final UserOpportunityPreferenceRepository preferences = mock(UserOpportunityPreferenceRepository.class);
    private final OutboxEventRepository outboxes = mock(OutboxEventRepository.class);
    private final DateNotificationMessageFactory messages = mock(DateNotificationMessageFactory.class);
    private final Clock clock = mock(Clock.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EventFanoutBatch batch = new EventFanoutBatch(events, opportunities, windows, preferences, outboxes,
            messages, new EventFanoutProperties(true, 500, 10, Duration.ofMinutes(30), Duration.ofMinutes(1), 5),
            clock, users);

    @Test
    void expiryInsideAPageEndsTheFanoutInsteadOfSkippingTheRestSilently() {
        var token = UUID.randomUUID();
        var event = mock(OpportunityEvent.class);
        var opportunity = mock(Opportunity.class);
        var version = mock(OpportunityVersion.class);
        when(events.lockForFanout(1L)).thenReturn(Optional.of(event));
        when(event.ownedBy(token)).thenReturn(true);
        when(event.getEventType()).thenReturn(OpportunityEventType.DEADLINE_SOON);
        when(event.isNotificationEligible()).thenReturn(true);
        when(event.getOpportunity()).thenReturn(opportunity);
        when(event.getEventId()).thenReturn(UUID.randomUUID());
        when(opportunity.getId()).thenReturn(10L);
        when(opportunity.getCurrentVersion()).thenReturn(version);
        when(version.getId()).thenReturn(20L);
        when(messages.supports(OpportunityEventType.DEADLINE_SOON)).thenReturn(true);
        when(opportunities.lockForNotification(10L)).thenReturn(Optional.of(opportunity));
        when(windows.findByVersionId(20L)).thenReturn(List.of());
        var prepared = new DateNotificationMessageFactory.Prepared("title", List.of("IN_APP"), EXPIRES_AT, Map.of());
        when(messages.prepare(eq(event), eq(opportunity), any(), any())).thenReturn(Optional.of(prepared));
        when(preferences.findSavedUserIdsAfter(eq(10L), anyLong(), any())).thenReturn(List.of(1L, 2L, 3L));
        var savedUsers = List.of(saved(1L), saved(2L), saved(3L));
        var liveUsers = savedUsers.stream().map(UserOpportunityPreference::getUser).toList();
        when(users.lockNotificationRecipients(List.of(1L, 2L, 3L))).thenReturn(liveUsers);
        when(preferences.lockSavedForNotification(eq(10L), any())).thenReturn(savedUsers);
        when(messages.create(eq(event), eq(1L), eq(prepared), any())).thenReturn(mock(OutboxEvent.class));
        // prepare() and the first user are before expiry; the clock passes it before the second user.
        when(clock.instant()).thenReturn(EXPIRES_AT.minusSeconds(2), EXPIRES_AT.minusSeconds(1), EXPIRES_AT);

        assertThat(batch.process(new EventFanoutExecution(1L, token))).isTrue();

        verify(outboxes, times(1)).save(any());
        verify(event).skipFanout(EXPIRES_AT, "EXPIRED_DURING_FANOUT");
        verify(event, never()).advanceFanout(anyLong(), any());
    }

    private static UserOpportunityPreference saved(long userId) {
        var user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        var preference = mock(UserOpportunityPreference.class);
        when(preference.getUser()).thenReturn(user);
        return preference;
    }
}
