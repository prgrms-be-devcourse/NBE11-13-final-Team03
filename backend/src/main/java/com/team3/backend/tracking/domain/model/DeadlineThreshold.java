package com.team3.backend.tracking.domain.model;

import java.util.Arrays;
import java.util.Optional;

public enum DeadlineThreshold {

    D_7(7, "D-7"),
    D_3(3, "D-3"),
    D_1(1, "D-1");

    private final long remainingDays;
    private final String code;

    DeadlineThreshold(
            long remainingDays,
            String code
    ) {
        this.remainingDays = remainingDays;
        this.code = code;
    }

    public long remainingDays() {
        return remainingDays;
    }

    public String code() {
        return code;
    }

    public static Optional<DeadlineThreshold> fromRemainingDays(
            long remainingDays
    ) {
        return Arrays.stream(values())
                .filter(threshold ->
                        threshold.remainingDays == remainingDays
                )
                .findFirst();
    }
}