package com.team3.dataservice.collection.service;

import org.junit.jupiter.api.Tag;

import com.team3.dataservice.collection.domain.entity.*;
import com.team3.dataservice.collection.domain.repository.*;
import com.team3.dataservice.collection.dto.request.RawRecordSaveRequest;
import java.time.OffsetDateTime;
import java.util.List;
import com.team3.dataservice.support.ApplicationPostgresTest;
import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.core.api.dataset.ExpectedDataSet;
import com.github.database.rider.junit5.api.DBRider;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;
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
@Tag("postgres")
class RawRecordBatchTransactionTests extends ApplicationPostgresTest {
    @Autowired JdbcTemplate db;
    @Autowired RawRecordService service;
    @Autowired SyncHistoryRepository histories;
    @Autowired RawExternalRecordRepository records;
    @Autowired RawNormalizationRequestRepository requests;
    @Autowired RawRecordFingerprint fingerprint;

    @AfterEach
    void clean() {
        db.execute("TRUNCATE collection_outbox_events, raw_normalization_request, raw_external_record, sync_history, external_source RESTART IDENTITY CASCADE");
    }

    @Test
    @DBRider(dataSourceBeanName = "dataSource")
    @DBUnit(schema = "application", qualifiedTableNames = true, caseSensitiveTableNames = true,
            cacheConnection = false, cacheTableNames = false, dataTypeFactoryClass = CollectionPostgresDataTypeFactory.class)
    @DataSet(value = "datasets/collection/running.yml", executorId = "raw-batch-postgres", transactional = false)
    @ExpectedDataSet("datasets/collection/batch-retried.yml")
    void rollsBackSnapshotsRequestsAndProgressBeforeIndividualRetry() {
        var now = OffsetDateTime.parse("2026-10-26T14:50:00Z");
        var sync = histories.findById(100L).orElseThrow();
        assertEquals("application_user", db.queryForObject("SELECT current_user", String.class));
        assertEquals(false, db.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class));
        assertEquals(false, db.queryForObject("SELECT has_schema_privilege(current_user, 'notification', 'USAGE')", Boolean.class));
        var payload = JsonNodeFactory.instance.objectNode().put("title", "A");
        var valid = new RawRecordSaveRequest(sync.getId(), "a", fingerprint.hash(payload), payload, now.plusSeconds(1));
        var invalid = new RawRecordSaveRequest(sync.getId(), "b", fingerprint.hash(payload), payload, now.minusSeconds(1));
        long rawBefore = records.count(), requestsBefore = requests.count();
        assertThrows(IllegalArgumentException.class, () -> service.saveBatchForSync(List.of(valid, invalid)));
        assertEquals(rawBefore, records.count());
        assertEquals(requestsBefore, requests.count());
        assertEquals(0, histories.findById(sync.getId()).orElseThrow().getCollectedCount());
        service.saveForSync(valid);
        assertEquals(rawBefore + 1, records.count());
        assertEquals(payload, records.findAll().getFirst().getPayload());
        assertEquals(requestsBefore + 1, requests.count());
        assertEquals(1, histories.findById(sync.getId()).orElseThrow().getNewCount());
    }
}
