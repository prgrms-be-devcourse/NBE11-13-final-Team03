package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.domain.CollectionSource;
import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.domain.entity.SyncHistory;
import com.team3.dataservice.outbox.domain.repository.CollectionOutboxEventRepository;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Records a durable message in the caller's collection-completion transaction; never sends it. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionCompletionOutbox {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<SyncJobStatus> COMPLETED = Set.of(
            SyncJobStatus.SUCCESS, SyncJobStatus.PARTIAL_SUCCESS, SyncJobStatus.FAILED);
    private final CollectionOutboxEventRepository outboxes;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIfDailyCompletion(SyncHistory history) {
        if (!"SCHEDULED".equals(history.getSyncType()) || !COMPLETED.contains(history.getStatus())) {
            return;
        }
        var source = CollectionSource.valueOf(history.getSource().getSourceCode()).name();
        var collectedDate = history.getStartedAt().atZoneSameInstant(KST).toLocalDate();
        var finishedAt = history.getFinishedAt().toInstant();
        var messageId = UUID.randomUUID();
        var envelope = Map.of(
                "schemaVersion", 1,
                "messageId", messageId.toString(),
                "eventType", "COLLECTION_COMPLETED",
                "correlationId", UUID.randomUUID().toString(),
                "occurredAt", finishedAt.toString(),
                "payload", Map.of(
                        "collectedDate", collectedDate.toString(),
                        "dataSource", source,
                        "newCount", history.getNewCount(),
                        "updatedCount", history.getUpdatedCount(),
                        "unchangedCount", history.getUnchangedCount(),
                        "failedCount", history.getFailedCount()));
        int inserted = outboxes.insertIfAbsent(messageId,
                "COLLECTION_COMPLETED:" + source + ":" + collectedDate,
                history.getId(), json.writeValueAsString(envelope), finishedAt);
        // This is an insert attempt; the surrounding transaction may still roll back.
        log.info("COLLECTION_COMPLETED insert: jobId={} source={} collectedDate={} messageId={} inserted={}",
                history.getId(), source, collectedDate, messageId, inserted);
    }
}
