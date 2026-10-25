package com.team3.notification.push;

import com.team3.notification.message.NotificationType;
import java.time.*;
import java.util.*;

/** Pure development-provisional-v1 policy. Does not register a timer, reserve a slot or call FCM. */
public final class PushSlotPolicy {
    public static final String VERSION = "development-push-slot-v1";
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final List<LocalTime> STARTS = List.of(LocalTime.of(9, 0), LocalTime.of(13, 30), LocalTime.of(17, 30));
    private PushSlotPolicy() { }

    public record Window(LocalDate date, int number, Instant start, Instant end) {
        public boolean contains(Instant now) { return !now.isBefore(start) && now.isBefore(end); }
    }

    public static Optional<Window> current(Instant now) {
        var date = now.atZone(KST).toLocalDate();
        for (int i = 0; i < STARTS.size(); i++) {
            var window = window(date, i);
            if (window.contains(now)) return Optional.of(window);
        }
        return Optional.empty();
    }

    /** Current window if open; otherwise the next slot, never a catch-up for a missed window. */
    public static Window next(Instant now) {
        var date = now.atZone(KST).toLocalDate();
        for (int i = 0; i < STARTS.size(); i++) {
            var window = window(date, i);
            if (now.isBefore(window.end())) return window;
        }
        return window(date.plusDays(1), 0);
    }

    /** Lower is more important. Absent means not a v1 push type; callers must separately require PUSH consent/channel. */
    public static OptionalInt priority(NotificationType type, String threshold) {
        if (type == null) return OptionalInt.empty();
        return switch (type) {
            case DEADLINE_SOON -> switch (Objects.requireNonNullElse(threshold, "")) {
                case "D-1" -> OptionalInt.of(0);
                case "D-3" -> OptionalInt.of(1);
                case "D-7" -> OptionalInt.of(4);
                default -> OptionalInt.empty();
            };
            case APPLICATION_OPENED -> OptionalInt.of(2);
            case DEADLINE_EXTENDED -> OptionalInt.of(3);
            case BECAME_ELIGIBLE -> OptionalInt.of(5);
            default -> OptionalInt.empty();
        };
    }

    private static Window window(LocalDate date, int index) {
        var start = date.atTime(STARTS.get(index)).atZone(KST).toInstant();
        return new Window(date, index + 1, start, start.plus(Duration.ofMinutes(30)));
    }
}
