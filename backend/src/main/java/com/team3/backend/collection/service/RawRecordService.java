package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.entity.RawExternalRecord;
import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.domain.RecordResultType;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.dto.response.RawRecordResponse;
import com.team3.backend.collection.domain.repository.ExternalSourceRepository;
import com.team3.backend.collection.domain.repository.RawExternalRecordRepository;
import com.team3.backend.collection.domain.repository.SyncHistoryRepository;
import com.team3.backend.collection.domain.entity.RawNormalizationRequest;
import com.team3.backend.collection.domain.repository.RawNormalizationRequestRepository;
import java.time.OffsetDateTime;
import jakarta.persistence.EntityNotFoundException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RawRecordService {
    private final SyncHistoryRepository syncHistories;
    private final ExternalSourceRepository sources;
    private final RawExternalRecordRepository records;
    private final RawRecordFingerprint fingerprint;
    private final RawNormalizationRequestRepository normalizationRequests;

    @Transactional
    public RawRecordResponse save(RawRecordSaveRequest request) {
        return saveInternal(request, false);
    }

    @Transactional
    public void saveForSync(RawRecordSaveRequest request) {
        saveInternal(request, true);
    }

    private RawRecordResponse saveInternal(RawRecordSaveRequest request, boolean recordProgress) {
        Objects.requireNonNull(request, "원본 저장 요청이 필요합니다.");
        var sync = syncHistories.findByIdForUpdate(request.syncId())
                .orElseThrow(() -> new EntityNotFoundException("수집 실행을 찾을 수 없습니다."));
        if (sync.getStatus() != SyncJobStatus.RUNNING) {
            throw new IllegalStateException("진행 중인 수집 실행에만 원본을 저장할 수 있습니다.");
        }
        if (request.collectedAt().isBefore(sync.getStartedAt())) {
            throw new IllegalArgumentException("수집 시각은 실행 시작 시각보다 빠를 수 없습니다.");
        }
        // ponytail: 출처 단위로 직렬화한다. 병목이 확인되면 외부 키별 현재 상태 행 잠금으로 변경한다.
        var source = sources.findByIdForUpdate(sync.getSource().getId())
                .orElseThrow(() -> new EntityNotFoundException("수집 출처를 찾을 수 없습니다."));
        var previous = records.findFirstBySourceIdAndExternalIdOrderByIdDesc(
                source.getId(), request.externalId());
        var payload = request.payload();
        var payloadHash = fingerprint.hash(payload);
        if (previous.isPresent()) {
            var latest = previous.get();
            if (request.collectedAt().isBefore(latest.getLastSeenAt())) {
                throw new IllegalArgumentException("마지막 관측 시각 이전의 원본은 적용할 수 없습니다.");
            }
            // 과거에 요청자가 지정한 해시를 저장했더라도 원본을 현재 서버 규칙으로 비교한다.
            boolean unchanged = fingerprint.hash(latest.getPayload()).equals(payloadHash);
            if (unchanged) {
                latest.markSeenAt(request.collectedAt());
                if (recordProgress) {
                    sync.recordSuccess(RecordResultType.UNCHANGED);
                }
                return RawRecordResponse.from(latest);
            }
        }
        var snapshot = new RawExternalRecord(sync, request.externalId(), payloadHash,
                payload, request.collectedAt());
        var saved = records.save(snapshot);
        if (recordProgress) {
            var resultType = previous.isPresent() ? RecordResultType.UPDATED : RecordResultType.CREATED;
            normalizationRequests.save(new RawNormalizationRequest(saved, previous.orElse(null), resultType, OffsetDateTime.now()));
            sync.recordSuccess(resultType);
        }
        return RawRecordResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public RawRecordResponse get(Long rawRecordId) {
        if (rawRecordId == null || rawRecordId < 1) {
            throw new IllegalArgumentException("원본 레코드 ID는 1 이상이어야 합니다.");
        }
        return RawRecordResponse.from(records.findById(rawRecordId)
                .orElseThrow(() -> new EntityNotFoundException("원본 레코드를 찾을 수 없습니다.")));
    }
}
