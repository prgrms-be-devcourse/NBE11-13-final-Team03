package com.team3.backend.collection.dto;

import com.team3.backend.collection.domain.ExternalSource;
import com.team3.backend.collection.domain.RawExternalRecord;
import com.team3.backend.collection.domain.SyncHistory;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.dto.response.RawRecordResponse;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class RawRecordDtoTests {
    private final OffsetDateTime observedAt = OffsetDateTime.parse("2026-10-16T10:00:00+09:00");
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void saveRequestPreservesValuesAndProtectsPayload() {
        var payload = mapper.createObjectNode().put("unknownField", "original");
        var request = new RawRecordSaveRequest(1L, " policy-1 ", "hash", payload, observedAt);
        payload.put("unknownField", "changed");
        assertEquals(" policy-1 ", request.externalId());
        assertEquals("original", request.payload().path("unknownField").asString());
        ((tools.jackson.databind.node.ObjectNode) request.payload()).put("unknownField", "changed again");
        assertEquals("original", request.payload().path("unknownField").asString());
    }

    @Test
    void saveRequestRejectsMissingIdentityOrPayload() {
        var payload = mapper.createObjectNode();
        assertThrows(IllegalArgumentException.class,
                () -> new RawRecordSaveRequest(0L, "id", "hash", payload, observedAt));
        assertThrows(IllegalArgumentException.class,
                () -> new RawRecordSaveRequest(1L, " ", "hash", payload, observedAt));
        assertThrows(IllegalArgumentException.class,
                () -> new RawRecordSaveRequest(1L, "id", " ", payload, observedAt));
        assertThrows(IllegalArgumentException.class,
                () -> new RawRecordSaveRequest(1L, "id", "hash", mapper.nullNode(), observedAt));
        assertThrows(NullPointerException.class,
                () -> new RawRecordSaveRequest(1L, "id", "hash", null, observedAt));
        assertThrows(NullPointerException.class,
                () -> new RawRecordSaveRequest(1L, "id", "hash", payload, null));
    }

    @Test
    void responseUsesSourceCodeAndSnapshotObservationTime() {
        var source = new ExternalSource("YOUTH_POLICY", "온통청년", "https://example.com", true, observedAt);
        var sync = new SyncHistory(source, "FULL", observedAt);
        var record = new RawExternalRecord(sync, "policy-1", "hash",
                mapper.createObjectNode().put("title", "original"), observedAt);

        var response = RawRecordResponse.from(record);

        assertEquals("YOUTH_POLICY", response.source());
        assertEquals("policy-1", response.externalId());
        assertEquals("hash", response.payloadHash());
        assertEquals(observedAt, response.collectedAt());
        assertEquals(observedAt, response.lastSeenAt());
        ((tools.jackson.databind.node.ObjectNode) response.payload()).put("title", "changed");
        assertEquals("original", record.getPayload().path("title").asString());
    }
}
