package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.RawExternalRecord;
import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.dto.response.RawRecordResponse;
import com.team3.backend.collection.repository.ExternalSourceRepository;
import com.team3.backend.collection.repository.RawExternalRecordRepository;
import com.team3.backend.collection.repository.SyncHistoryRepository;
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

    @Transactional
    public RawRecordResponse save(RawRecordSaveRequest request) {
        Objects.requireNonNull(request, "원본 저장 요청이 필요합니다.");
        var sync = syncHistories.findByIdForUpdate(request.syncId())
                .orElseThrow(() -> new IllegalArgumentException("수집 실행을 찾을 수 없습니다."));
        if (sync.getStatus() != SyncJobStatus.RUNNING) {
            throw new IllegalStateException("진행 중인 수집 실행에만 원본을 저장할 수 있습니다.");
        }
        if (request.collectedAt().isBefore(sync.getStartedAt())) {
            throw new IllegalArgumentException("수집 시각은 실행 시작 시각보다 빠를 수 없습니다.");
        }
        // ponytail: 출처 단위로 직렬화한다. 병목이 확인되면 외부 키별 현재 상태 행 잠금으로 변경한다.
        var source = sources.findByIdForUpdate(sync.getSource().getId())
                .orElseThrow(() -> new IllegalArgumentException("수집 출처를 찾을 수 없습니다."));
        var previous = records.findFirstBySourceIdAndExternalIdOrderByIdDesc(
                source.getId(), request.externalId());
        if (previous.isPresent()) {
            var latest = previous.get();
            if (request.collectedAt().isBefore(latest.getLastSeenAt())) {
                throw new IllegalArgumentException("마지막 관측 시각 이전의 원본은 적용할 수 없습니다.");
            }
            if (latest.getPayloadHash().equals(request.payloadHash())) {
                latest.markSeenAt(request.collectedAt());
                return RawRecordResponse.from(latest);
            }
        }
        var snapshot = new RawExternalRecord(sync, request.externalId(), request.payloadHash(),
                request.payload(), request.collectedAt());
        return RawRecordResponse.from(records.save(snapshot));
    }
}
