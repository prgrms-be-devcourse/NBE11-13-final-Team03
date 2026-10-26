package com.team3.notification.service;

import com.team3.notification.config.NotificationPushProperties;
import com.team3.notification.push.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationSlotWorkerTest {
    private static final Instant NOW=Instant.parse("2026-10-23T00:00:00Z");
    private final NotificationSlotStore store=mock(NotificationSlotStore.class);
    private final PushSender sender=mock(PushSender.class);
    private final Clock clock=mock(Clock.class);
    private final NotificationPushProperties props=new NotificationPushProperties(false,"","",2,5,
            Duration.ofMinutes(2),Duration.ofMinutes(1),Duration.ofSeconds(10));
    private final NotificationSlotWorker worker=new NotificationSlotWorker(store,sender,props,clock);
    private final NotificationSlotStore.Claim claim=new NotificationSlotStore.Claim(1,2,7,UUID.randomUUID());
    private final PushMessage message=new PushMessage(1,3,7,4,claim.token(),"private-token","title","body","/test-inbox",
            NOW.plusSeconds(1800),UUID.randomUUID(),UUID.randomUUID(),2L);
    private void ready() {
        when(clock.instant()).thenReturn(NOW);
        when(store.claimNext()).thenReturn(Optional.of(claim),Optional.empty());
        when(store.prepare(claim)).thenReturn(Optional.of(message));
    }

    @Test void pollHasBoundedDeliveryCallsAndIsolatesBuildFailure() {
        when(store.candidates()).thenReturn(List.of(7L,8L)); when(store.build(7)).thenThrow(new IllegalStateException("private SQL body"));
        when(store.claimNext()).thenReturn(Optional.of(claim)); when(store.prepare(claim)).thenReturn(Optional.empty());
        worker.runOnce(); verify(store).build(8); verify(store,times(2)).claimNext(); verifyNoInteractions(sender);
    }

    @Test void finalClockCheckPreventsCallAtExclusiveWindowEnd() {
        ready(); when(clock.instant()).thenReturn(message.expiresAt()); worker.runOnce(); verifyNoInteractions(sender);
        verify(store).failed(eq(claim),eq(message),argThat(f->f.kind()==PushFailure.Kind.PERMANENT && f.getMessage().equals("PUSH_EXPIRED")));
    }

    @Test void invalidReceiptIsRetriedRatherThanRecordedAsSent() {
        ready(); when(sender.send(message)).thenReturn(" "); worker.runOnce();
        verify(store).failed(eq(claim),eq(message),argThat(f->f.kind()==PushFailure.Kind.TRANSIENT));
        verify(store,never()).sent(any(),any());
    }

    @Test void completionFailureIsNotPretendedToRollbackAnExternalSend() {
        ready(); when(sender.send(message)).thenReturn("accepted"); when(store.sent(claim,"accepted")).thenThrow(new IllegalStateException("database offline"));
        worker.runOnce(); verify(sender).send(message); verify(store,never()).failed(any(),any(),any());
        worker.runOnce(); verify(store,times(2)).expiredBundles(); // Guard released after the failed completion.
    }

    @Test void configurationFailureStopsRemainingBatchAndNextPollCanRun() {
        ready(); when(store.claimNext()).thenReturn(Optional.of(claim));
        when(sender.send(message)).thenThrow(new PushFailure(PushFailure.Kind.CONFIGURATION,"FCM_CONFIGURATION_ERROR"));
        worker.runOnce(); verify(sender,times(1)).send(message); verify(store,times(1)).claimNext();
        worker.runOnce(); verify(store,times(2)).claimNext();
    }

    @Test void concurrentManualInvocationDoesNotDoublePoll() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(store.expiredBundles()).thenAnswer(call->{ entered.countDown();
            if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); return List.of(); });
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(worker::runOnce);
            try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); worker.runOnce(); }
            finally { release.countDown(); }
            first.get(10,TimeUnit.SECONDS);
        }
        verify(store,times(1)).expiredBundles();
    }
}
