package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CancellationException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class ManualSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(ManualSyncWorker.class);
    private final SyncJobService jobs;
    private final YouthPolicyCollectionService youthPolicy;
    private final Employment24CollectionService employment24;
    private final RawRecordService rawRecords;
    private final RawRecordFingerprint fingerprint;

    public void run(Long jobId, CollectionSource source, int pageSize, int maxPages) {
        try {
            jobs.start(jobId);
            List<JsonNode> records;
            boolean completed;
            if (source == CollectionSource.YOUTH_POLICY) {
                var result = youthPolicy.collect(pageSize, maxPages);
                records = result.records();
                completed = result.completed();
                jobs.recordCollection(jobId, records.size(), result.pageFailures(), List.of(), List.of());
            } else {
                var result = employment24.collect(LocalDate.now(ZoneId.of("Asia/Seoul")), pageSize, maxPages);
                records = result.records();
                completed = result.completed();
                jobs.recordCollection(jobId, records.size(), List.of(), result.pageFailures(), result.incompleteNcsCodes());
            }
            for (int index = 0; index < records.size(); index++) {
                checkInterrupted();
                String externalId = null;
                try {
                    var payload = records.get(index);
                    externalId = fingerprint.externalId(source, payload);
                    rawRecords.saveForSync(new RawRecordSaveRequest(jobId, externalId, fingerprint.hash(payload),
                            payload, OffsetDateTime.now()));
                } catch (IllegalArgumentException exception) {
                    jobs.recordFailure(jobId, index, externalId, "INVALID_RECORD");
                } catch (org.springframework.dao.DataAccessException exception) {
                    jobs.recordFailure(jobId, index, externalId, "STORAGE_FAILED");
                }
            }
            checkInterrupted();
            jobs.complete(jobId, completed);
        } catch (RuntimeException exception) {
            boolean interrupted = Thread.currentThread().isInterrupted() || exception instanceof CancellationException;
            log.warn("jobId={} source={} exceptionType={}", jobId, source, exception.getClass().getSimpleName());
            try {
                jobs.fail(jobId, interrupted ? "수집 실행이 중단되었습니다." : "수집 실행 중 오류가 발생했습니다.", interrupted);
            } catch (RuntimeException persistenceFailure) {
                log.error("jobId={} statusUpdateFailure={}", jobId, persistenceFailure.getClass().getSimpleName());
            }
        }
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("수집 실행이 중단되었습니다.");
        }
    }
}
