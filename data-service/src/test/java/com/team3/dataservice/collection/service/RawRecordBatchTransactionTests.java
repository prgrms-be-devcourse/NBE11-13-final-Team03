package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.domain.entity.*;
import com.team3.dataservice.collection.domain.repository.*;
import com.team3.dataservice.collection.dto.request.RawRecordSaveRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.node.JsonNodeFactory;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.test.database.replace=NONE",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.type.json_format_mapper=org.hibernate.type.format.jackson.Jackson3JsonFormatMapper"
})
@ActiveProfiles("test")
@Import({RawRecordService.class, RawRecordFingerprint.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RawRecordBatchTransactionTests {
    @Autowired RawRecordService service;
    @Autowired ExternalSourceRepository sources;
    @Autowired SyncHistoryRepository histories;
    @Autowired RawExternalRecordRepository records;
    @Autowired RawNormalizationRequestRepository requests;
    @Autowired RawRecordFingerprint fingerprint;

    @Test
    void rollsBackSnapshotsRequestsAndProgressBeforeIndividualRetry() {
        var now = OffsetDateTime.now();
        var source = sources.save(new ExternalSource("BATCH_TEST", "batch", "https://example.com", true, now));
        var sync = histories.save(new SyncHistory(source, "FULL", now));
        var payload = JsonNodeFactory.instance.objectNode().put("title", "A");
        var valid = new RawRecordSaveRequest(sync.getId(), "a", fingerprint.hash(payload), payload, now.plusSeconds(1));
        var invalid = new RawRecordSaveRequest(sync.getId(), "b", fingerprint.hash(payload), payload, now.minusSeconds(1));
        long rawBefore = records.count(), requestsBefore = requests.count();
        try {
            assertThrows(IllegalArgumentException.class, () -> service.saveBatchForSync(List.of(valid, invalid)));
            assertEquals(rawBefore, records.count());
            assertEquals(requestsBefore, requests.count());
            assertEquals(0, histories.findById(sync.getId()).orElseThrow().getCollectedCount());
            service.saveForSync(valid);
            assertEquals(rawBefore + 1, records.count());
            assertEquals(requestsBefore + 1, requests.count());
            assertEquals(1, histories.findById(sync.getId()).orElseThrow().getNewCount());
        } finally {
            requests.deleteAllInBatch();
            records.deleteAllInBatch();
            histories.deleteById(sync.getId());
            sources.deleteById(source.getId());
        }
    }
}
