package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.domain.entity.SyncHistory;
import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.domain.RecordProcessStatus;
import com.team3.backend.collection.dto.response.Employment24PageFailureResponse;
import com.team3.backend.collection.dto.response.SyncJobResponse;
import com.team3.backend.collection.dto.response.SyncPageFailureResponse;
import com.team3.backend.collection.dto.response.SyncRecordResultResponse;
import com.team3.backend.collection.dto.response.SyncStartResponse;
import com.team3.backend.collection.domain.repository.ExternalSourceRepository;
import com.team3.backend.collection.domain.repository.SyncHistoryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncJobService {
    private static final List<SyncJobStatus> ACTIVE = List.of(SyncJobStatus.PENDING, SyncJobStatus.RUNNING);
    private final ExternalSourceRepository sources;
    private final SyncHistoryRepository histories;
    private final OffsetDateTime applicationStartedAt = OffsetDateTime.now();

    @Value("${collection.youth-policy.url}")
    private String youthPolicyUrl;

    @Value("${collection.employment24.url}")
    private String employment24Url;

    @Transactional
    public SyncStartResponse create(CollectionSource sourceType) {
        boolean youth = sourceType == CollectionSource.YOUTH_POLICY;
        sources.registerIfAbsent(sourceType.name(), youth ? "온통청년" : "고용24",
                youth ? youthPolicyUrl : employment24Url);
        var registered = sources.findBySourceCode(sourceType.name()).orElseThrow();
        var source = sources.findByIdForUpdate(registered.getId()).orElseThrow();
        if (!source.isEnabled()) {
            throw new IllegalStateException("사용 중지된 출처는 최신화할 수 없습니다.");
        }
        if (histories.existsBySourceIdAndStatusIn(source.getId(), ACTIVE)) {
            throw new IllegalStateException("해당 출처의 최신화가 이미 대기 중이거나 실행 중입니다.");
        }
        var history = histories.save(SyncHistory.pendingManual(source, OffsetDateTime.now()));
        return new SyncStartResponse(history.getId(), sourceType.name(), history.getStatus(), "수동 최신화 요청을 접수했습니다.");
    }

    @Transactional(readOnly = true)
    public SyncJobResponse get(Long jobId) {
        if (jobId == null || jobId < 1) {
            throw new IllegalArgumentException("수집 실행 ID는 1 이상이어야 합니다.");
        }
        return SyncJobResponse.from(histories.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("수집 실행을 찾을 수 없습니다.")));
    }

    @Transactional
    public void start(Long jobId) {
        locked(jobId).start();
    }

    @Transactional
    public void recordCollection(Long jobId, int count, List<SyncPageFailureResponse> pageFailures,
                                 List<Employment24PageFailureResponse> employment24PageFailures,
                                 List<String> incompleteNcsCodes) {
        locked(jobId).recordCollection(count, pageFailures, employment24PageFailures, incompleteNcsCodes);
    }

    @Transactional
    public void recordFailure(Long jobId, int itemIndex, String externalId, String errorCode) {
        locked(jobId).recordFailure(new SyncRecordResultResponse(null, externalId, null, itemIndex, null,
                RecordProcessStatus.FAILED, null, errorCode, "원본 항목 처리에 실패했습니다.", OffsetDateTime.now()));
    }

    @Transactional
    public void complete(Long jobId, boolean completed) {
        locked(jobId).finish(completed, OffsetDateTime.now());
    }

    @Transactional
    public void fail(Long jobId, String safeReason, boolean interrupted) {
        locked(jobId).fail(safeReason, interrupted, OffsetDateTime.now());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterrupted() {
        // ponytail: 단일 앱 인스턴스의 재시작 복구다. 다중 인스턴스 전환 시 소유권·임대 기반 복구가 필요하다.
        for (var history : histories.findBySyncTypeAndStatusInAndStartedAtBefore("MANUAL", ACTIVE, applicationStartedAt)) {
            history.fail("애플리케이션 재시작으로 수집 실행이 중단되었습니다.", true, OffsetDateTime.now());
        }
    }

    private SyncHistory locked(Long jobId) {
        return histories.findByIdForUpdate(jobId)
                .orElseThrow(() -> new EntityNotFoundException("수집 실행을 찾을 수 없습니다."));
    }
}
