package com.team3.dataservice.collection.service;

import java.util.concurrent.CancellationException;

/** Retries stay inside one collection execution; only the exhausted result is recorded. */
final class CollectionRetry {
    static final int MAX_ATTEMPTS = 3;

    private CollectionRetry() { }

    static void beforeAttempt(int attempt) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("수집 실행이 중단되었습니다.");
        }
        if (attempt > 1) {
            try {
                Thread.sleep(100L * (attempt - 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("수집 실행이 중단되었습니다.");
            }
        }
    }
}
