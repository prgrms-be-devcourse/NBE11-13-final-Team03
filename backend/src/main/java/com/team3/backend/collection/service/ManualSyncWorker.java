package com.team3.backend.collection.service;

import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.dto.external.CollectionSummary;
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
            CollectionSummary summary;
            if (source == CollectionSource.YOUTH_POLICY) {
                summary = youthPolicy.collectPages(pageSize, maxPages,
                        (offset, page) -> processPage(jobId, source, offset, page));
            } else {
                summary = employment24.collectPages(LocalDate.now(ZoneId.of("Asia/Seoul")), pageSize, maxPages,
                        (offset, page) -> processPage(jobId, source, offset, page));
            }
            jobs.recordCollection(jobId, summary.fetchedCount(), summary.pageFailures(),
                    summary.employment24PageFailures(), summary.incompleteNcsCodes());
            checkInterrupted();
            jobs.complete(jobId, summary.completed());
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

    private void processPage(Long jobId, CollectionSource source, int offset, List<JsonNode> records) {
        checkInterrupted();
        if (records.isEmpty()) {
            return;
        }
        jobs.recordFetchedPage(jobId, records.size());
        for (int start = 0; start < records.size(); start += 100) {
            var batch = records.subList(start, Math.min(start + 100, records.size()));
            checkInterrupted();
            try {
                var requests = batch.stream().map(payload -> new RawRecordSaveRequest(jobId,
                        fingerprint.externalId(source, payload), payload, OffsetDateTime.now())).toList();
                rawRecords.saveBatchForSync(requests);
            } catch (IllegalArgumentException | org.springframework.dao.DataAccessException exception) {
                // The proxied batch transaction has rolled back before retrying individual records.
                processIndividually(jobId, source, offset + start, batch);
            }
        }
    }

    private void processIndividually(Long jobId, CollectionSource source, int offset, List<JsonNode> records) {
        for (int index = 0; index < records.size(); index++) {
            checkInterrupted();
            String externalId = null;
            try {
                var payload = records.get(index);
                externalId = fingerprint.externalId(source, payload);
                rawRecords.saveForSync(new RawRecordSaveRequest(jobId, externalId,
                        payload, OffsetDateTime.now()));
            } catch (IllegalArgumentException exception) {
                jobs.recordFailure(jobId, offset + index, externalId, "INVALID_RECORD");
            } catch (org.springframework.dao.DataAccessException exception) {
                jobs.recordFailure(jobId, offset + index, externalId, "STORAGE_FAILED");
            }
        }
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("수집 실행이 중단되었습니다.");
        }
    }
}
