package com.team3.backend.tracking.service;

import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.domain.repository.RawNormalizationRequestRepository;
import com.team3.backend.collection.domain.repository.SyncHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

@Service
@RequiredArgsConstructor
public class DateTrackingReadiness {
    private final SyncHistoryRepository histories;
    private final RawNormalizationRequestRepository requests;

    @Transactional(readOnly = true)
    public boolean ready(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime until = date.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
        var today = histories.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDescIdDesc(
                from, until).stream().filter(h -> h.getSource().getSourceCode().equals("YOUTH_POLICY")).toList();
        boolean collected = !today.isEmpty() && today.getFirst().getStatus() == SyncJobStatus.SUCCESS;
        boolean collecting = histories.existsBySourceSourceCodeAndStatusIn("YOUTH_POLICY",
                List.of(SyncJobStatus.PENDING, SyncJobStatus.RUNNING));
        return collected && !collecting && !requests.hasPendingBefore("YOUTH_POLICY", until);
    }
}
