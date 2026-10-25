package com.team3.notification.push;

import com.team3.notification.message.NotificationType;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PushSlotPolicyTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 25);
    private Instant kst(int hour, int minute) { return DATE.atTime(hour, minute).atZone(PushSlotPolicy.KST).toInstant(); }

    @Test
    void allThreeWindowsIncludeStartButExcludeEnd() {
        int number = 0;
        for (var start : List.of(kst(9, 0), kst(13, 30), kst(17, 30))) {
            number++;
            assertThat(PushSlotPolicy.current(start.minusNanos(1))).isEmpty();
            var window = PushSlotPolicy.current(start).orElseThrow();
            assertThat(window.number()).isEqualTo(number); assertThat(window.date()).isEqualTo(DATE);
            assertThat(window.end()).isEqualTo(start.plusSeconds(1800));
            assertThat(window.contains(window.end().minusNanos(1))).isTrue();
            assertThat(PushSlotPolicy.current(window.end())).isEmpty();
        }
    }

    @Test
    void utcInstantIsInterpretedInKstAndNeverByServerLocalZone() {
        var window = PushSlotPolicy.current(Instant.parse("2026-10-25T00:00:00Z")).orElseThrow();
        assertThat(window.date()).isEqualTo(DATE); assertThat(window.number()).isEqualTo(1);
        assertThat(window.start()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
        assertThat(PushSlotPolicy.next(Instant.parse("2026-10-24T15:00:00Z")).date()).isEqualTo(DATE);
    }

    @Test
    void missedWindowIsNotCaughtUpAndEveningGoesToNextDay() {
        assertThat(PushSlotPolicy.next(kst(8, 59)).start()).isEqualTo(kst(9, 0));
        assertThat(PushSlotPolicy.next(kst(9, 10)).start()).isEqualTo(kst(9, 0));
        assertThat(PushSlotPolicy.next(kst(9, 30)).start()).isEqualTo(kst(13, 30));
        assertThat(PushSlotPolicy.next(kst(14, 0)).start()).isEqualTo(kst(17, 30));
        assertThat(PushSlotPolicy.current(kst(18, 0))).isEmpty();
        assertThat(PushSlotPolicy.next(kst(18, 0)).start()).isEqualTo(kst(9, 0).plus(Duration.ofDays(1)));
        assertThat(PushSlotPolicy.next(kst(20, 0)).number()).isEqualTo(1);
    }

    @Test
    void monthAndYearRolloverUseTheNextKstDate() {
        var evening = LocalDate.of(2026, 12, 31).atTime(23, 59).atZone(PushSlotPolicy.KST).toInstant();
        assertThat(PushSlotPolicy.next(evening).date()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void supportedPrioritiesFollowProvisionalV1WithoutRankingInAppOnlyTypes() {
        assertThat(PushSlotPolicy.priority(NotificationType.DEADLINE_SOON, "D-1").orElseThrow()).isEqualTo(0);
        assertThat(PushSlotPolicy.priority(NotificationType.DEADLINE_SOON, "D-3").orElseThrow()).isEqualTo(1);
        assertThat(PushSlotPolicy.priority(NotificationType.APPLICATION_OPENED, null).orElseThrow()).isEqualTo(2);
        assertThat(PushSlotPolicy.priority(NotificationType.DEADLINE_EXTENDED, null).orElseThrow()).isEqualTo(3);
        assertThat(PushSlotPolicy.priority(NotificationType.DEADLINE_SOON, "D-7").orElseThrow()).isEqualTo(4);
        assertThat(PushSlotPolicy.priority(NotificationType.BECAME_ELIGIBLE, null).orElseThrow()).isEqualTo(5);
        for (var type : List.of(NotificationType.APPLICATION_CLOSED, NotificationType.ELIGIBILITY_DOWNGRADED, NotificationType.ELIGIBILITY_REVIEW_REQUIRED)) {
            assertThat(PushSlotPolicy.priority(type, null).isEmpty()).isTrue();
        }
    }

    @Test
    void unknownThresholdDoesNotBecomeAnUnintendedPushCandidate() {
        for (String threshold : List.of("", "D-2", "D-01")) {
            assertThat(PushSlotPolicy.priority(NotificationType.DEADLINE_SOON, threshold).isEmpty()).isTrue();
        }
        assertThat(PushSlotPolicy.priority(NotificationType.DEADLINE_SOON, null).isEmpty()).isTrue();
        assertThat(PushSlotPolicy.priority(null, "D-1").isEmpty()).isTrue();
    }
}
