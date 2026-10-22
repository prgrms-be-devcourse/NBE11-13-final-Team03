package com.team3.backend.collection.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.domain.SyncJobStatus;
import com.team3.backend.collection.domain.RecordResultType;
import com.team3.backend.collection.domain.entity.ExternalSource;
import com.team3.backend.collection.domain.entity.SyncHistory;
import com.team3.backend.collection.dto.response.SyncJobResponse;
import com.team3.backend.collection.dto.response.SyncPageFailureResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 외부 API는 로컬 HTTP 서버, DB는 테스트 전용 PostgreSQL 컨테이너를 사용한다. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "collection.schedule.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@WithMockUser(authorities = "ADMIN")
class ManualSyncControllerIntegrationTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    private static final String PATH = "/api/admin/collection/syncs";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final java.util.concurrent.ExecutorService HTTP_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer UPSTREAM = server();
    private static volatile String youthRecords = "[]";
    private static volatile String youthSecondRecords = "[]";
    private static volatile int blockedYouthPage;
    private static volatile String employmentRecords = "[]";
    private static volatile int youthFailurePage;
    private static volatile int youthResultCode;
    private static volatile int employmentFailureCode;
    private static volatile int youthTotalOverride;
    private static volatile CountDownLatch youthRelease = new CountDownLatch(0);
    private static volatile CountDownLatch youthEntered = new CountDownLatch(0);
    private static final AtomicInteger EMPLOYMENT_CALLS = new AtomicInteger();
    private static final AtomicInteger YOUTH_CALLS = new AtomicInteger();

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
        youthResultCode = 0;
        employmentFailureCode = 0;
        youthTotalOverride = -1;
        youthSecondRecords = "[]";
        blockedYouthPage = 0;
        youthRelease = new CountDownLatch(0);
        youthEntered = new CountDownLatch(0);
        EMPLOYMENT_CALLS.set(0);
        YOUTH_CALLS.set(0);
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
    void listsEveryStatusByKoreanDateWithStableNewestFirstOrder() throws Exception {
        var from = OffsetDateTime.parse("2026-10-20T15:00:00Z");
        var until = from.plusDays(1);
        var youth = sources.save(new ExternalSource("YOUTH_POLICY", "온통청년", "https://example.test", true, from));
        var employment = sources.save(new ExternalSource("EMPLOYMENT24", "고용24", "https://example.test", true, from));
        history(youth, SyncJobStatus.FAILED, from.minusNanos(1000));
        var success = history(youth, SyncJobStatus.SUCCESS, from);
        var pending = history(youth, SyncJobStatus.PENDING, from.plusHours(1));
        var running = history(youth, SyncJobStatus.RUNNING, from.plusHours(2));
        var partial = history(youth, SyncJobStatus.PARTIAL_SUCCESS, from.plusHours(3));
        var failed = history(employment, SyncJobStatus.FAILED, until.minusNanos(1000));
        var interrupted = history(youth, SyncJobStatus.INTERRUPTED, until.minusNanos(1000));
        history(youth, SyncJobStatus.SUCCESS, until);

        var result = mvc.perform(get(PATH).param("date", "2026-10-21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].status").value("INTERRUPTED"))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].source").value("EMPLOYMENT24"))
                .andExpect(jsonPath("$[1].failureReason").value("테스트 수집 실패"))
                .andExpect(jsonPath("$[2].status").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$[2].failedPageCount").value(1))
                .andExpect(jsonPath("$[2].pageFailures[0].errorCode").value("HTTP_500"))
                .andExpect(jsonPath("$[3].status").value("RUNNING"))
                .andExpect(jsonPath("$[3].completedAt").doesNotExist())
                .andExpect(jsonPath("$[4].status").value("PENDING"))
                .andExpect(jsonPath("$[5].status").value("SUCCESS"))
                .andReturn();
        assertEquals(List.of(interrupted.getId(), failed.getId(), partial.getId(), running.getId(), pending.getId(), success.getId()),
                body(result).findValues("jobId").stream().map(JsonNode::asLong).toList());
    }

    private SyncHistory history(ExternalSource source, SyncJobStatus status, OffsetDateTime startedAt) {
        var history = SyncHistory.pending(source, status == SyncJobStatus.SUCCESS ? "MANUAL" : "SCHEDULED", startedAt);
        switch (status) {
            case RUNNING -> history.start();
            case SUCCESS, PARTIAL_SUCCESS -> {
                history.start();
                if (status == SyncJobStatus.PARTIAL_SUCCESS) {
                    history.recordSuccess(RecordResultType.CREATED);
                    history.recordCollection(1, List.of(new SyncPageFailureResponse(2, "HTTP_500", "외부 API 오류", true)), List.of(), List.of());
                }
                history.finish(status == SyncJobStatus.SUCCESS, startedAt.plusMinutes(1));
            }
            case FAILED, INTERRUPTED -> history.fail("테스트 수집 실패", status == SyncJobStatus.INTERRUPTED, startedAt.plusMinutes(1));
            default -> { }
        }
        return histories.save(history);
    }

    @Test
    void returnsEmptyHistoryForDateWithoutJobs() throws Exception {
        mvc.perform(get(PATH).param("date", "2026-10-21"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void rejectsMissingOrInvalidHistoryDates() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isBadRequest());
        for (var date : List.of("", "invalid", "2026-02-30", "0000-01-01", "+1000000-01-01", "+999999999-12-31")) {
            mvc.perform(get(PATH).param("date", date)).andExpect(status().isBadRequest());
        }
        assertEquals(0, histories.count());
    }

    @Test
    void historyListRequiresAdmin() throws Exception {
        mvc.perform(get(PATH).param("date", "2026-10-21").with(anonymous()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).param("date", "2026-10-21").with(user("member")
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("USER"))))
                .andExpect(status().isForbidden());
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
    void firstPageOutageStopsAfterThreeCallsAndPersistsFailedHistory() throws Exception {
        youthFailurePage = 1;
        var result = awaitDone(start("YOUTH_POLICY"));
        assertEquals(SyncJobStatus.FAILED, result.status());
        assertEquals(3, YOUTH_CALLS.get());
        assertEquals(1, result.failedPageCount());
        assertEquals("HTTP_503", result.pageFailures().getFirst().errorCode());
        assertEquals(0, records.count());
    }

    @Test
    void firstPageApiErrorStopsImmediatelyAndPersistsFailedHistory() throws Exception {
        youthFailurePage = 1;
        youthResultCode = 401;
        var result = awaitDone(start("YOUTH_POLICY"));
        assertEquals(SyncJobStatus.FAILED, result.status());
        assertEquals(1, YOUTH_CALLS.get());
        assertEquals("API_ERROR", result.pageFailures().getFirst().errorCode());
        assertEquals(0, records.count());
    }

    @Test
    void middlePageApiErrorPreservesSavedRecordsAndEndsAsPartialSuccess() throws Exception {
        youthFailurePage = 2;
        youthResultCode = 403;
        youthTotalOverride = 1000;
        var result = awaitDone(start("YOUTH_POLICY", 1));
        assertEquals(SyncJobStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(2, YOUTH_CALLS.get());
        assertEquals(1, result.successCount());
        assertEquals(2, result.pageFailures().getFirst().pageNumber());
        assertEquals(1, records.count());
        assertEquals(1, normalizationRequests.count());
    }

    @Test
    void failedScheduledAttemptIsRecognizedOnlyOnItsKoreanCalendarDay() {
        long id = jobs.createScheduled(CollectionSource.YOUTH_POLICY).jobId();
        jobs.fail(id, "API 장애", false);
        var from = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        assertTrue(histories.existsBySourceSourceCodeAndSyncTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                "YOUTH_POLICY", "SCHEDULED", from, from.plusDays(1)));
        assertFalse(histories.existsBySourceSourceCodeAndSyncTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                "YOUTH_POLICY", "MANUAL", from, from.plusDays(1)));
        assertFalse(histories.existsBySourceSourceCodeAndSyncTypeAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                "YOUTH_POLICY", "SCHEDULED", from.plusDays(1), from.plusDays(2)));
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
        mvc.perform(post(PATH + "/YOUTH_POLICY").with(user("member").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("USER"))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/YOUTH_POLICY")).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/1").with(user("member").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("USER")))).andExpect(status().isForbidden());
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
        YOUTH_CALLS.incrementAndGet();
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
            respond(exchange, youthResultCode == 0 ? 503 : 200,
                    "{\"resultCode\":" + youthResultCode + "}");
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
