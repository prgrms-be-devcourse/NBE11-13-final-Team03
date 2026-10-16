package com.team3.backend.collection.controller;

import com.team3.backend.collection.domain.entity.ExternalSource;
import com.team3.backend.collection.domain.entity.SyncHistory;
import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.domain.repository.ExternalSourceRepository;
import com.team3.backend.collection.domain.repository.RawExternalRecordRepository;
import com.team3.backend.collection.domain.repository.SyncHistoryRepository;
import com.team3.backend.collection.domain.repository.RawNormalizationRequestRepository;
import com.team3.backend.collection.service.RawRecordService;
import java.time.OffsetDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 반드시 전용 DB를 지정한다. create-drop과 각 테스트의 전체 데이터 삭제를 수행한다. */
@EnabledIfEnvironmentVariable(named = "YON_TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(properties = {
        "spring.datasource.url=${YON_TEST_DATABASE_URL}",
        "spring.datasource.username=${YON_TEST_DATABASE_USERNAME:yon_test}",
        "spring.datasource.password=${YON_TEST_DATABASE_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class RawRecordControllerIntegrationTests {
    private static final String PATH = "/api/admin/collection/raw-records";
    private final OffsetDateTime startedAt = OffsetDateTime.parse("2026-10-16T10:00:00+09:00");
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private ExternalSourceRepository sources;
    @Autowired private SyncHistoryRepository syncHistories;
    @Autowired private RawNormalizationRequestRepository normalizationRequests;
    @Autowired private RawExternalRecordRepository records;
    @Autowired private RawRecordService service;
    @Autowired private JdbcTemplate jdbc;
    private ExternalSource source;
    private SyncHistory sync;

    @BeforeEach
    void setUp() {
        normalizationRequests.deleteAllInBatch();
        records.deleteAllInBatch();
        syncHistories.deleteAllInBatch();
        sources.deleteAllInBatch();
        source = sources.save(new ExternalSource("YOUTH_POLICY", "온통청년", "https://example.com", true, startedAt));
        sync = syncHistories.save(new SyncHistory(source, "FULL", startedAt));
    }

    private RawRecordSaveRequest request(Long syncId, String value, int minute) {
        return new RawRecordSaveRequest(syncId, "policy-1", "hash-" + value,
                mapper.createObjectNode().put("title", value).put("unknownField", "원본 유지"),
                startedAt.plusMinutes(minute));
    }

    private MvcResult save(String value, int minute) throws Exception {
        return mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(sync.getId(), value, minute))))
                .andExpect(status().isOk()).andReturn();
    }

    private JsonNode body(MvcResult result) {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void httpRequestPersistsJsonbAndCanBeReadAfterCommit() throws Exception {
        var response = body(save("청년 지원", 1));
        long id = response.path("rawRecordId").asLong();
        assertEquals("YOUTH_POLICY", response.path("source").asString());
        assertEquals("원본 유지", response.path("payload").path("unknownField").asString());
        assertEquals("object", jdbc.queryForObject("select jsonb_typeof(payload) from raw_external_record where id = ?", String.class, id));
        assertEquals(sync.getId(), jdbc.queryForObject("select sync_id from raw_external_record where id = ?", Long.class, id));
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.title").value("청년 지원"));
    }

    @Test
    void repeatedContentUpdatesOnlyLastSeenAndKeepsCreationExecution() throws Exception {
        var first = body(save("A", 1));
        var secondSync = syncHistories.save(new SyncHistory(source, "FULL", startedAt.plusMinutes(2)));
        var repeated = mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(secondSync.getId(), "A", 3))))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.path("rawRecordId"), body(repeated).path("rawRecordId"));
        assertEquals(1, records.count());
        assertEquals(startedAt.plusMinutes(1).toInstant(), records.findAll().getFirst().getFirstSeenAt().toInstant());
        assertEquals(startedAt.plusMinutes(3).toInstant(), records.findAll().getFirst().getLastSeenAt().toInstant());
        assertEquals(sync.getId(), jdbc.queryForObject("select sync_id from raw_external_record", Long.class));
    }

    @Test
    void preservesAllSnapshotsWhenContentChangesBack() throws Exception {
        save("A", 1);
        save("B", 2);
        save("A", 3);
        assertEquals(3, records.count());
        assertEquals("A", jdbc.queryForObject("select payload ->> 'title' from raw_external_record order by id limit 1", String.class));
    }

    @Test
    void rejectsInvalidBodyWithoutWriting() throws Exception {
        mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        assertEquals(0, records.count());
    }

    @Test
    void missingExecutionAndRecordReturnNotFound() throws Exception {
        mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(Long.MAX_VALUE, "A", 1))))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/0")).andExpect(status().isBadRequest());
        assertEquals(0, records.count());
    }

    @Test
    void rejectsFinishedExecution() throws Exception {
        jdbc.update("update sync_history set status = ? where id = ?", SyncJobStatus.SUCCESS.name(), sync.getId());
        mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(sync.getId(), "A", 1))))
                .andExpect(status().isConflict());
        assertEquals(0, records.count());
    }

    @Test
    void rejectsStaleContentWithoutChangingDatabase() throws Exception {
        save("A", 2);
        mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(sync.getId(), "B", 1))))
                .andExpect(status().isBadRequest());
        assertEquals(1, records.count());
        assertEquals("hash-A", records.findAll().getFirst().getPayloadHash());
    }

    @Test
    void authenticationAdminRoleAndCsrfAreRequired() throws Exception {
        String json = mapper.writeValueAsString(request(sync.getId(), "A", 1));
        mvc.perform(post(PATH).with(anonymous()).with(csrf()).accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(user("member").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/1").with(user("member").roles("USER"))).andExpect(status().isForbidden());
        assertEquals(0, records.count());
    }

    @Test
    void databaseFailureRollsBackAndDoesNotExposeSql() throws Exception {
        jdbc.execute("alter table raw_external_record add constraint reject_test_payload check (payload_hash <> 'hash-rejected')");
        try {
            mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request(sync.getId(), "rejected", 1))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("원본 데이터 저장소 처리에 실패했습니다."));
            assertEquals(0, records.count());
        } finally {
            jdbc.execute("alter table raw_external_record drop constraint reject_test_payload");
        }
        save("A", 2);
        assertEquals(1, records.count());
    }

    @Test
    void concurrentFirstObservationsAcrossExecutionsProduceOneSnapshot() throws Exception {
        var otherSync = syncHistories.save(new SyncHistory(source, "FULL", startedAt));
        var barrier = new CyclicBarrier(2);
        Callable<Long> first = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return service.save(request(sync.getId(), "A", 1)).rawRecordId();
        };
        Callable<Long> second = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return service.save(request(otherSync.getId(), "A", 1)).rawRecordId();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(first);
            var secondResult = executor.submit(second);
            assertEquals(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, records.count());
    }
}
