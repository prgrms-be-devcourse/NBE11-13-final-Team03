package com.team3.backend.tracking.service;

import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.domain.entity.ExternalSource;
import com.team3.backend.collection.domain.entity.SyncHistory;
import com.team3.backend.collection.domain.repository.RawNormalizationRequestRepository;
import com.team3.backend.collection.domain.repository.SyncHistoryRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DateTrackingReadinessTest {
    private final SyncHistoryRepository histories = mock(SyncHistoryRepository.class);
    private final RawNormalizationRequestRepository requests = mock(RawNormalizationRequestRepository.class);
    private final DateTrackingReadiness service = new DateTrackingReadiness(histories, requests);
    private final LocalDate date = LocalDate.of(2026, 10, 20);

    @Test
    void requiresSuccessfulYouthPolicyCollectionNotJustAnEmptyQueue() {
        assertThat(service.ready(date)).isFalse();
        histories(List.of(success("EMPLOYMENT24")));
        assertThat(service.ready(date)).isFalse();
        histories(List.of(success("YOUTH_POLICY")));
        assertThat(service.ready(date)).isTrue();
    }

    @Test
    void pendingOrFailedNormalizationBlocksReadiness() {
        histories(List.of(success("YOUTH_POLICY")));
        when(requests.hasIncompleteBefore(eq("YOUTH_POLICY"), any())).thenReturn(true);
        assertThat(service.ready(date)).isFalse();
    }

    @Test
    void anotherActiveCollectionOrLatestFailureBlocksReadiness() {
        histories(List.of(success("YOUTH_POLICY")));
        when(histories.existsBySourceSourceCodeAndStatusIn(eq("YOUTH_POLICY"), any())).thenReturn(true);
        assertThat(service.ready(date)).isFalse();
        when(histories.existsBySourceSourceCodeAndStatusIn(eq("YOUTH_POLICY"), any())).thenReturn(false);
        var failed = new SyncHistory(source("YOUTH_POLICY"), "SCHEDULED", OffsetDateTime.now());
        failed.fail("failure", false, OffsetDateTime.now());
        histories(List.of(failed, success("YOUTH_POLICY")));
        assertThat(service.ready(date)).isFalse();
    }

    private void histories(List<SyncHistory> rows) {
        when(histories.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDescIdDesc(
                any(), any())).thenReturn(rows);
    }

    private SyncHistory success(String code) {
        var history = new SyncHistory(source(code), "SCHEDULED", OffsetDateTime.now());
        history.finish(true, OffsetDateTime.now());
        return history;
    }

    private ExternalSource source(String code) {
        return new ExternalSource(code, code, "https://example.test", true, OffsetDateTime.now());
    }
}
