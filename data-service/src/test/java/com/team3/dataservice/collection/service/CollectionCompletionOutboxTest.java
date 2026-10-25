package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.domain.RecordResultType;
import com.team3.dataservice.collection.domain.SyncJobStatus;
import com.team3.dataservice.collection.domain.entity.ExternalSource;
import com.team3.dataservice.collection.domain.entity.SyncHistory;
import com.team3.dataservice.collection.dto.response.SyncPageFailureResponse;
import com.team3.dataservice.collection.dto.response.SyncRecordResultResponse;
import com.team3.dataservice.collection.domain.RecordProcessStatus;
import com.team3.dataservice.outbox.domain.repository.CollectionOutboxEventRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionCompletionOutboxTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-26T14:50:00Z");
    private static final OffsetDateTime FINISH = OffsetDateTime.parse("2026-10-26T15:10:00Z");
    private final CollectionOutboxEventRepository events = mock(CollectionOutboxEventRepository.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final CollectionCompletionOutbox completion = new CollectionCompletionOutbox(events, json);

    @ParameterizedTest
    @EnumSource(value = SyncJobStatus.class, names = {"SUCCESS", "PARTIAL_SUCCESS", "FAILED"})
    void recordsEachScheduledTerminalOutcome(SyncJobStatus status) {
        var history = history("SCHEDULED", status);
        completion.recordIfDailyCompletion(history);
        var body = capture();
        assertThat(body.get("eventType").asText()).isEqualTo("COLLECTION_COMPLETED");
    }

    @ParameterizedTest
    @EnumSource(value = SyncJobStatus.class, names = {"PENDING", "RUNNING", "INTERRUPTED"})
    void ignoresNonCompletedScheduledJobs(SyncJobStatus status) {
        completion.recordIfDailyCompletion(history("SCHEDULED", status));
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @EnumSource(value = SyncJobStatus.class, names = {"SUCCESS", "PARTIAL_SUCCESS", "FAILED"})
    void ignoresManualJobsEvenWhenFinished(SyncJobStatus status) {
        completion.recordIfDailyCompletion(history("MANUAL", status));
        verifyNoInteractions(events);
    }

    @Test
    void usesStartDateInKoreaAndFinishedInstantInUtcWithExactlySixPayloadFields() throws Exception {
        var history = history("SCHEDULED", SyncJobStatus.RUNNING);
        history.recordCollection(4, List.of(new SyncPageFailureResponse(2, "HTTP_500", "private", true)),
                List.of(), List.of("01"));
        history.recordSuccess(RecordResultType.CREATED);
        history.recordSuccess(RecordResultType.UPDATED);
        history.recordSuccess(RecordResultType.UNCHANGED);
        history.recordFailure(new SyncRecordResultResponse(null, "p1", null, 3, null,
                RecordProcessStatus.FAILED, null, "STORAGE_FAILED", "private", FINISH));
        history.finish(false, FINISH);
        completion.recordIfDailyCompletion(history);

        var body = capture();
        var sample = json.readTree(new ClassPathResource("contracts/collection-completed-v1.json").getInputStream());
        assertThat(json.convertValue(body, Map.class).keySet())
                .isEqualTo(json.convertValue(sample, Map.class).keySet());
        assertThat(json.convertValue(body.get("payload"), Map.class)).isEqualTo(Map.of(
                "collectedDate", "2026-10-26", "dataSource", "EMPLOYMENT24",
                "newCount", 1, "updatedCount", 1, "unchangedCount", 1, "failedCount", 1));
        assertThat(json.convertValue(body.get("payload"), Map.class).keySet())
                .isEqualTo(json.convertValue(sample.get("payload"), Map.class).keySet());
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.get("occurredAt").asText()).isEqualTo(FINISH.toInstant().toString());
        assertThat(UUID.fromString(body.get("correlationId").asText())).isNotNull();
        assertThat(UUID.fromString(body.get("messageId").asText())).isNotNull();
    }

    @Test
    void startsNewCollectionDateAtKoreanMidnight() {
        var history = new SyncHistory(source(), "SCHEDULED", START.withHour(15).withMinute(0));
        ReflectionTestUtils.setField(history, "id", 7L);
        history.finish(true, FINISH);
        completion.recordIfDailyCompletion(history);
        verify(events).insertIfAbsent(any(UUID.class), eq("COLLECTION_COMPLETED:EMPLOYMENT24:2026-10-27"),
                eq(7L), anyString(), eq(FINISH.toInstant()));
    }

    private tools.jackson.databind.JsonNode capture() {
        var body = ArgumentCaptor.forClass(String.class);
        var id = ArgumentCaptor.forClass(UUID.class);
        verify(events).insertIfAbsent(id.capture(), eq("COLLECTION_COMPLETED:EMPLOYMENT24:2026-10-26"),
                eq(7L), body.capture(), any(Instant.class));
        var envelope = json.readTree(body.getValue());
        assertThat(envelope.get("messageId").asText()).isEqualTo(id.getValue().toString());
        return envelope;
    }

    private ExternalSource source() {
        return new ExternalSource("EMPLOYMENT24", "고용24", "https://example.test", true, START);
    }

    private SyncHistory history(String type, SyncJobStatus status) {
        var history = SyncHistory.pending(source(), type, START);
        ReflectionTestUtils.setField(history, "id", 7L);
        switch (status) {
            case PENDING -> { }
            case RUNNING -> history.start();
            case SUCCESS -> { history.start(); history.finish(true, FINISH); }
            case PARTIAL_SUCCESS -> {
                history.start(); history.recordSuccess(RecordResultType.CREATED); history.finish(false, FINISH);
            }
            case FAILED, INTERRUPTED -> history.fail("private", status == SyncJobStatus.INTERRUPTED, FINISH);
        }
        return history;
    }
}
