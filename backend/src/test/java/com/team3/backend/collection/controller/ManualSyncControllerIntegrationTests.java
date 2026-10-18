package com.team3.backend.collection.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.dto.response.SyncJobResponse;
import com.team3.backend.collection.domain.repository.ExternalSourceRepository;
import com.team3.backend.collection.domain.repository.RawExternalRecordRepository;
import com.team3.backend.collection.domain.repository.SyncHistoryRepository;
import com.team3.backend.collection.domain.repository.RawNormalizationRequestRepository;
import com.team3.backend.collection.service.SyncJobService;
import com.team3.backend.collection.service.RawRecordService;
import com.team3.backend.collection.service.RawRecordFingerprint;
import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import java.time.OffsetDateTime;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 외부 API만 로컬 HTTP 서버로 대체한다. 나머지는 실제 Spring 빈과 전용 PostgreSQL을 사용한다. */
@EnabledIfEnvironmentVariable(named = "YON_TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(properties = {
        "collection.schedule.enabled=false",
        "spring.datasource.url=${YON_TEST_DATABASE_URL}",
        "spring.datasource.username=${YON_TEST_DATABASE_USERNAME:yon_test}",
        "spring.datasource.password=${YON_TEST_DATABASE_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class ManualSyncControllerIntegrationTests {
    private static final String PATH = "/api/admin/collection/syncs";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final java.util.concurrent.ExecutorService HTTP_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer UPSTREAM = server();
    private static volatile String youthRecords = "[]";
    private static volatile String youthSecondRecords = "[]";
    private static volatile int blockedYouthPage;
    private static volatile String employmentRecords = "[]";
    private static volatile int youthFailurePage;
    private static volatile int employmentFailureCode;
    private static volatile int youthTotalOverride;
    private static volatile CountDownLatch youthRelease = new CountDownLatch(0);
    private static volatile CountDownLatch youthEntered = new CountDownLatch(0);
    private static final AtomicInteger EMPLOYMENT_CALLS = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private SyncJobService jobs;
    @Autowired private SyncHistoryRepository histories;
    @Autowired private RawNormalizationRequestRepository normalizationRequests;
    @Autowired private RawExternalRecordRepository records;
    @Autowired private ExternalSourceRepository sources;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RawRecordService rawRecords;
    @Autowired private RawRecordFingerprint fingerprint;

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        registry.add("collection.youth-policy.url", () -> base + "/youth");
        registry.add("collection.employment24.url", () -> base + "/employment");
        registry.add("collection.youth-policy.api-key", () -> UUID.randomUUID().toString());
        registry.add("collection.employment24.api-key", () -> UUID.randomUUID().toString());
    }

    @BeforeEach
    void setUp() {
        normalizationRequests.deleteAllInBatch();
        records.deleteAllInBatch();
        histories.deleteAllInBatch();
        sources.deleteAllInBatch();
        youthRecords = "[{\"plcyNo\":\"p1\",\"title\":\"A\",\"nested\":{\"z\":2,\"a\":1}}]";
        employmentRecords = "[]";
        youthFailurePage = 0;
        employmentFailureCode = 0;
        youthTotalOverride = -1;
        youthSecondRecords = "[]";
        blockedYouthPage = 0;
        youthRelease = new CountDownLatch(0);
        youthEntered = new CountDownLatch(0);
        EMPLOYMENT_CALLS.set(0);
    }

    @AfterEach
    void finishWorkers() throws Exception {
        youthRelease.countDown();
        for (var history : histories.findBySyncTypeAndStatusIn("MANUAL",
                List.of(SyncJobStatus.PENDING, SyncJobStatus.RUNNING))) {
            awaitDone(history.getId());
        }
    }

    @AfterAll
    static void stopServer() {
        UPSTREAM.stop(0);
        HTTP_EXECUTOR.close();
    }

    private long start(String source) throws Exception {
        return start(source, 100);
    }

    private long start(String source, int pageSize) throws Exception {
        var result = mvc.perform(post(PATH + "/" + source).with(csrf())
                        .param("pageSize", String.valueOf(pageSize)).param("maxPages", "2"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        long id = body(result).path("jobId").asLong();
        assertEquals(PATH + "/" + id, result.getResponse().getHeader("Location"));
        return id;
    }

    private SyncJobResponse awaitDone(long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            var response = jobs.get(id);
            if (response.completedAt() != null) {
                return response;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("최신화가 제한 시간 안에 종료되지 않았습니다.");
        return null;
    }

    private JsonNode body(MvcResult result) {
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void acceptsBeforeCollectionFinishesAndRejectsDuplicateSource() throws Exception {
        youthRelease = new CountDownLatch(1);
        youthEntered = new CountDownLatch(1);
        long id = start("YOUTH_POLICY");
        assertTrue(youthEntered.await(5, TimeUnit.SECONDS));
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        mvc.perform(post(PATH + "/YOUTH_POLICY").with(csrf())).andExpect(status().isConflict());
        assertEquals(SyncJobStatus.SUCCESS, awaitDone(start("EMPLOYMENT24")).status());
        youthRelease.countDown();
        var result = awaitDone(id);
        assertEquals(SyncJobStatus.SUCCESS, result.status());
        assertEquals(1, result.newCount());
        assertEquals(1, result.successCount());
        assertEquals(0, result.pendingCount());
        assertEquals(1, records.count());
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("YOUTH_POLICY"))
                .andExpect(jsonPath("$.newCount").value(1));
    }

    @Test
    void canonicalRecollectionAndTwoChangesPreserveThreeSnapshots() throws Exception {
        assertEquals(1, awaitDone(start("YOUTH_POLICY")).newCount());
        assertEquals(1, normalizationRequests.count());
        youthRecords = "[{\"nested\":{\"a\":1,\"z\":2},\"title\":\"A\",\"plcyNo\":\"p1\"}]";
        assertEquals(1, awaitDone(start("YOUTH_POLICY")).unchangedCount());
        assertEquals(1, normalizationRequests.count());
        youthRecords = youthRecords.replace("\"A\"", "\"B\"");
        assertEquals(1, awaitDone(start("YOUTH_POLICY")).updatedCount());
        youthRecords = youthRecords.replace("\"B\"", "\"A\"");
        assertEquals(1, awaitDone(start("YOUTH_POLICY")).updatedCount());
        assertEquals(3, records.count());
        var requests = normalizationRequests.findAll().stream()
                .sorted(java.util.Comparator.comparing(com.team3.backend.collection.domain.entity.RawNormalizationRequest::getId))
                .toList();
        assertEquals(List.of(com.team3.backend.collection.domain.RecordResultType.CREATED,
                        com.team3.backend.collection.domain.RecordResultType.UPDATED,
                        com.team3.backend.collection.domain.RecordResultType.UPDATED),
                requests.stream().map(com.team3.backend.collection.domain.entity.RawNormalizationRequest::getEventType).toList());
        assertTrue(requests.stream().allMatch(request -> request.getStatus().equals("PENDING")));
        assertNull(requests.getFirst().getPreviousRawRecord());
        assertEquals(requests.get(0).getRawRecord().getId(), requests.get(1).getPreviousRawRecord().getId());
        assertEquals(requests.get(1).getRawRecord().getId(), requests.get(2).getPreviousRawRecord().getId());
        assertEquals("p1", records.findAll().getFirst().getExternalId());
    }

    @Test
    void differentLegacyHashAloneDoesNotProduceAChange() throws Exception {
        awaitDone(start("YOUTH_POLICY"));
        jdbc.update("update raw_external_record set payload_hash = 'legacy-hash'");
        var result = awaitDone(start("YOUTH_POLICY"));
        assertEquals(1, result.unchangedCount());
        assertEquals(0, result.updatedCount());
        assertEquals(1, records.count());
    }

    @Test
    void keepsEmploymentCourseRoundsDistinctAndPreservesOriginalFields() throws Exception {
        employmentRecords = """
                [{"trprId":"course-1","trprDegr":"01","unknown":{"a":null}},
                 {"trprId":"course-1","trprDegr":"02","unknown":{"a":null}}]
                """;
        var result = awaitDone(start("EMPLOYMENT24"));
        assertEquals(SyncJobStatus.SUCCESS, result.status());
        assertEquals(2, result.newCount());
        assertEquals(2, normalizationRequests.count());
        assertEquals(24, EMPLOYMENT_CALLS.get());
        assertEquals(2, records.count());
        assertEquals(2, records.findAll().stream().map(record -> record.getExternalId()).distinct().count());
        assertTrue(records.findAll().getFirst().getPayload().path("unknown").path("a").isNull());
    }

    @Test
    void malformedRecordIsRecordedWithoutStoppingValidRecords() throws Exception {
        youthRecords = "[{\"title\":\"missing-id\"},{\"plcyNo\":\"p1\",\"title\":\"valid\"}]";
        long id = start("YOUTH_POLICY");
        var result = awaitDone(id);
        assertEquals(SyncJobStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(2, result.fetchedCount());
        assertEquals(1, result.successCount());
        assertEquals(1, result.failedRecordCount());
        assertEquals(0, result.recordFailures().getFirst().itemIndex());
        assertEquals("INVALID_RECORD", result.recordFailures().getFirst().errorCode());
        assertEquals(1, records.count());
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recordFailures[0].errorCode").value("INVALID_RECORD"));
    }

    @Test
    void pageFailureIsNotReportedAsSuccessAndIsPersistedWithRequestConditions() throws Exception {
        employmentFailureCode = 503;
        employmentRecords = "[{\"trprId\":\"course-1\",\"trprDegr\":\"1\"}]";
        var result = awaitDone(start("EMPLOYMENT24"));
        assertEquals(SyncJobStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(1, result.failedPageCount());
        assertEquals(List.of("01"), result.incompleteNcsCodes());
        assertEquals("01", result.employment24PageFailures().getFirst().request().srchNcs1());
        assertEquals("HTTP_503", result.employment24PageFailures().getFirst().errorCode());
        assertEquals(26, EMPLOYMENT_CALLS.get());
    }

    @Test
    void authenticationFailureTerminatesEmploymentCollectionAndLeavesFailedHistory() throws Exception {
        employmentFailureCode = 401;
        var result = awaitDone(start("EMPLOYMENT24"));
        assertEquals(SyncJobStatus.FAILED, result.status());
        assertEquals(1, EMPLOYMENT_CALLS.get());
        assertEquals(24, result.incompleteNcsCodes().size());
        assertEquals(0, records.count());
    }

    @Test
    void pageLimitWithSavedRecordsIsPartialSuccess() throws Exception {
        youthTotalOverride = 1000;
        var result = mvc.perform(post(PATH + "/YOUTH_POLICY").with(csrf()).param("maxPages", "1"))
                .andExpect(status().isAccepted()).andReturn();
        assertEquals(SyncJobStatus.PARTIAL_SUCCESS, awaitDone(body(result).path("jobId").asLong()).status());
    }

    @Test
    void storageFailureRollsBackBothSnapshotAndSuccessCounter() throws Exception {
        youthRecords = "[{\"plcyNo\":\"bad\",\"title\":\"reject\"},{\"plcyNo\":\"good\",\"title\":\"keep\"}]";
        jdbc.execute("alter table raw_external_record add constraint reject_manual_test check (payload ->> 'title' <> 'reject')");
        try {
            var result = awaitDone(start("YOUTH_POLICY"));
            assertEquals(SyncJobStatus.PARTIAL_SUCCESS, result.status());
            assertEquals(1, result.newCount());
            assertEquals(1, result.failedRecordCount());
            assertEquals("STORAGE_FAILED", result.recordFailures().getFirst().errorCode());
            assertEquals(1, records.count());
            assertEquals(1, normalizationRequests.count());
        } finally {
            jdbc.execute("alter table raw_external_record drop constraint reject_manual_test");
        }
    }

    @Test
    void firstPageIsCommittedAndQueuedWhileSecondPageIsStillLoading() throws Exception {
        youthRelease = new CountDownLatch(1);
        youthEntered = new CountDownLatch(1);
        blockedYouthPage = 2;
        youthTotalOverride = 2;
        youthSecondRecords = "[{\"plcyNo\":\"p2\",\"title\":\"second\"}]";
        long id = start("YOUTH_POLICY", 1);
        try {
            assertTrue(youthEntered.await(5, TimeUnit.SECONDS));
            assertEquals(SyncJobStatus.RUNNING, jobs.get(id).status());
            assertEquals(1, jobs.get(id).fetchedCount());
            assertEquals(1, jobs.get(id).successCount());
            assertEquals(1, records.count());
            assertEquals(1, normalizationRequests.count());
            var queued = normalizationRequests.findAll().getFirst();
            assertEquals("PENDING", queued.getStatus());
            assertEquals(records.findAll().getFirst().getId(), queued.getRawRecord().getId());
        } finally {
            youthRelease.countDown();
        }
        assertEquals(SyncJobStatus.SUCCESS, awaitDone(id).status());
        assertEquals(2, normalizationRequests.count());
    }

    @Test
    void requestInsertionFailureRollsBackRawAndCounterAndAllowsRetry() throws Exception {
        jdbc.execute("alter table raw_normalization_request add constraint reject_queue_test check (event_type <> 'CREATED')");
        try {
            var result = awaitDone(start("YOUTH_POLICY"));
            assertEquals(SyncJobStatus.FAILED, result.status());
            assertEquals(0, result.newCount());
            assertEquals(1, result.failedRecordCount());
            assertEquals(0, records.count());
            assertEquals(0, normalizationRequests.count());
        } finally {
            jdbc.execute("alter table raw_normalization_request drop constraint reject_queue_test");
        }
        assertEquals(1, awaitDone(start("YOUTH_POLICY")).newCount());
        assertEquals(1, normalizationRequests.count());
    }

    @Test
    void restartRecoveryPreservesProgressAndAllowsNextRun() throws Exception {
        long id = jobs.create(CollectionSource.YOUTH_POLICY).jobId();
        jobs.start(id);
        jobs.recordCollection(id, 3, List.of(), List.of(), List.of());
        var payload = MAPPER.readTree(youthRecords).get(0);
        rawRecords.saveForSync(new RawRecordSaveRequest(id, "p1", fingerprint.hash(payload), payload, OffsetDateTime.now()));
        jdbc.update("update sync_history set started_at = ? where id = ?", OffsetDateTime.parse("2020-01-01T00:00:00Z"), id);
        jobs.recoverInterrupted();
        assertEquals(SyncJobStatus.INTERRUPTED, jobs.get(id).status());
        assertNotNull(jobs.get(id).completedAt());
        assertEquals(1, jobs.get(id).successCount());
        assertEquals(2, jobs.get(id).pendingCount());
        assertEquals(SyncJobStatus.SUCCESS, awaitDone(start("YOUTH_POLICY")).status());
    }

    @Test
    void simultaneousFirstRequestsCreateOnlyOneActiveJob() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<Boolean> create = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                jobs.create(CollectionSource.YOUTH_POLICY);
                return true;
            } catch (IllegalStateException conflict) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, histories.count());
        } finally {
            for (var history : histories.findBySyncTypeAndStatusIn("MANUAL", List.of(SyncJobStatus.PENDING))) {
                jobs.fail(history.getId(), "테스트 종료", true);
            }
        }
    }

    @Test
    void invalidSourceAndLimitsDoNotCreateHistory() throws Exception {
        mvc.perform(post(PATH + "/UNKNOWN").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "/YOUTH_POLICY").with(csrf()).param("pageSize", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/0")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
        assertEquals(0, histories.count());
    }

    @Test
    void requiresAdminAndCsrfBeforeStartingJobs() throws Exception {
        mvc.perform(post(PATH + "/YOUTH_POLICY").with(anonymous()).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/YOUTH_POLICY").with(user("member").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/YOUTH_POLICY")).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/1").with(user("member").roles("USER"))).andExpect(status().isForbidden());
        assertEquals(0, histories.count());
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(HTTP_EXECUTOR);
            server.createContext("/youth", ManualSyncControllerIntegrationTests::youth);
            server.createContext("/employment", ManualSyncControllerIntegrationTests::employment);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        return Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }

    private static void youth(HttpExchange exchange) throws IOException {
        var query = query(exchange);
        int page = Integer.parseInt(query.get("pageNum"));
        if (blockedYouthPage == 0 || page == blockedYouthPage) {
            youthEntered.countDown();
            try {
                if (!youthRelease.await(10, TimeUnit.SECONDS)) {
                    respond(exchange, 503, "{}");
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        if (page == youthFailurePage) {
            respond(exchange, 503, "{}");
            return;
        }
        int count = youthTotalOverride >= 0 ? youthTotalOverride : MAPPER.readTree(youthRecords).size();
        respond(exchange, 200, "{\"resultCode\":200,\"result\":{\"youthPolicyList\":"
                + (page == 1 ? youthRecords : youthSecondRecords) + ",\"pagging\":{\"pageNum\":" + page
                + ",\"pageSize\":" + query.get("pageSize") + ",\"totCount\":" + count + "}}}");
    }

    private static void employment(HttpExchange exchange) throws IOException {
        EMPLOYMENT_CALLS.incrementAndGet();
        var query = query(exchange);
        if (query.get("srchNcs1").equals("01") && employmentFailureCode > 0) {
            respond(exchange, employmentFailureCode, "{}");
            return;
        }
        String rows = query.get("srchNcs1").equals("02") ? employmentRecords : "[]";
        respond(exchange, 200, "{\"scn_cnt\":" + MAPPER.readTree(rows).size() + ",\"pageNum\":"
                + query.get("pageNum") + ",\"pageSize\":" + query.get("pageSize") + ",\"srchList\":" + rows + "}");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        try (exchange) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
