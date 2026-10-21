package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.repository.EligibilityResultUpsertRepository.UpsertRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판정 결과 일괄 UPSERT를 실제 PostgreSQL로 검증한다. 동시 저장을 보려고 테스트 트랜잭션 없이 각자 커밋한다.
 * 일회용 DB를 YON_TEST_DB_URL로 지정할 것 (ddl-auto=create-drop).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "YON_TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${YON_TEST_DB_URL}",
        "spring.datasource.username=${YON_TEST_DB_USERNAME:postgres}",
        "spring.datasource.password=${YON_TEST_DB_PASSWORD:1234}",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EligibilityResultUpsertRepositoryTest {

    private static final Long USER = 1L;
    private static final String RULE = "1";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 15, 10, 0);
    private static final LocalDate VALID_UNTIL = LocalDate.of(2027, 5, 15);

    @Autowired
    private EligibilityResultRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM eligibility_results");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM eligibility_results");
    }

    @Test
    void 처음_저장하면_모든_행을_넣는다() {
        assertThat(upsert(1L, rows(3, 10L, EligibilityStatus.ELIGIBLE), AT)).isEqualTo(3);

        assertThat(jdbc.queryForList("SELECT opportunity_id, opportunity_version_id, profile_version, rule_version, "
                + "eligibility_status, reason, condition_results::text AS c, valid_until, evaluated_at "
                + "FROM eligibility_results ORDER BY opportunity_id"))
                .hasSize(3)
                .first().satisfies(r -> {
                    assertThat(r.get("opportunity_id")).isEqualTo(1L);
                    assertThat(r.get("opportunity_version_id")).isEqualTo(10L);
                    assertThat(r.get("profile_version")).isEqualTo(1L);
                    assertThat(r.get("rule_version")).isEqualTo(RULE);
                    assertThat(r.get("eligibility_status")).isEqualTo("ELIGIBLE");
                    assertThat(r.get("reason")).isEqualTo("사유 1");
                    assertThat((String) r.get("c")).contains("\"result\": \"MATCHED\"").contains("\"expected\": 34");
                    assertThat(r.get("valid_until")).isEqualTo(java.sql.Date.valueOf(VALID_UNTIL));
                    assertThat(r.get("evaluated_at")).isEqualTo(java.sql.Timestamp.valueOf(AT));
                });
        // 저장한 jsonb를 엔티티로 읽을 수 있다
        assertThat(repository.findByUserIdAndOpportunityIdIn(USER, List.of(1L))).singleElement()
                .satisfies(r -> assertThat(r.getConditionResults()).singleElement()
                        .satisfies(m -> assertThat(m).containsEntry("result", "MATCHED").containsEntry("expected", 34)));
    }

    @Test
    void 같은_내용을_다시_저장하면_쓰지_않는다() {
        upsert(1L, rows(3, 10L, EligibilityStatus.ELIGIBLE), AT);
        List<Map<String, Object>> before = dump();

        assertThat(upsert(1L, rows(3, 10L, EligibilityStatus.ELIGIBLE), AT.plusHours(1))).isZero();
        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 정책_버전이_바뀌면_같은_행을_갱신한다() {
        upsert(1L, rows(2, 10L, EligibilityStatus.ELIGIBLE), AT);
        List<Long> ids = jdbc.queryForList("SELECT id FROM eligibility_results ORDER BY opportunity_id", Long.class);

        assertThat(upsert(1L, rows(2, 11L, EligibilityStatus.NOT_ELIGIBLE), AT.plusHours(1))).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id FROM eligibility_results ORDER BY opportunity_id", Long.class))
                .isEqualTo(ids);
        assertThat(jdbc.queryForList("SELECT DISTINCT opportunity_version_id || ':' || eligibility_status "
                + "|| ':' || evaluated_at FROM eligibility_results", String.class))
                .containsExactly("11:NOT_ELIGIBLE:" + "2026-10-15 11:00:00");
    }

    @Test
    void 더_낮은_정책_버전은_덮어쓰지_않는다() {
        upsert(1L, rows(2, 11L, EligibilityStatus.ELIGIBLE), AT);

        assertThat(upsert(1L, rows(2, 10L, EligibilityStatus.NOT_ELIGIBLE), AT.plusHours(1))).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT opportunity_version_id || ':' || eligibility_status "
                + "FROM eligibility_results", String.class))
                .containsExactly("11:ELIGIBLE");
    }

    @Test
    void 프로필_버전이_오르면_갱신하고_더_낮은_버전은_덮어쓰지_않는다() {
        upsert(1L, rows(2, 10L, EligibilityStatus.ELIGIBLE), AT);
        assertThat(upsert(2L, rows(2, 10L, EligibilityStatus.NOT_ELIGIBLE), AT.plusHours(1))).isEqualTo(2);

        // 늦게 끝난 버전 1 계산
        assertThat(upsert(1L, rows(2, 10L, EligibilityStatus.ELIGIBLE), AT.plusHours(2))).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT profile_version || ':' || eligibility_status "
                + "FROM eligibility_results", String.class)).containsExactly("2:NOT_ELIGIBLE");
    }

    @Test
    void 규칙_버전이_바뀌면_갱신한다() {
        upsert(1L, rows(2, 10L, EligibilityStatus.ELIGIBLE), AT);

        Integer written = tx.execute(s -> repository.upsert(USER, 1L, "2", AT, rows(2, 10L, EligibilityStatus.ELIGIBLE)));
        assertThat(written).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT DISTINCT rule_version FROM eligibility_results", String.class))
                .containsExactly("2");
    }

    @Test
    void 이유와_유효기한이_없어도_저장한다() {
        List<UpsertRow> rows = List.of(new UpsertRow(1L, 10L, EligibilityStatus.UNKNOWN, null, List.of(), null));

        assertThat(upsert(1L, rows, AT)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT reason, valid_until, condition_results::text AS c FROM eligibility_results"))
                .containsEntry("reason", null).containsEntry("valid_until", null).containsEntry("c", "[]");
    }

    @Test
    void 동시에_같은_결과를_저장해도_충돌하지_않고_한_번만_쓴다() throws Exception {
        int n = 500;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<Integer> save = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return upsert(1L, rows(n, 10L, EligibilityStatus.ELIGIBLE), AT);
            };
            Future<Integer> a = pool.submit(save);
            Future<Integer> b = pool.submit(save);

            assertThat(a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS)).isEqualTo(n);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eligibility_results", Long.class)).isEqualTo(n);
    }

    @Test
    void 오래된_계산이_먼저_잠그고_늦게_커밋해도_최신_결과가_남는다() throws Exception {
        int n = 200;
        upsert(1L, rows(n, 10L, EligibilityStatus.ELIGIBLE), AT);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch newerCommitted = new CountDownLatch(1);
        try {
            // 버전 2 계산이 먼저 커밋되고, 그 뒤 버전 1 계산이 쓰려고 하는 순서 역전
            Future<Integer> newer = pool.submit(() -> {
                int written = upsert(2L, rows(n, 10L, EligibilityStatus.NOT_ELIGIBLE), AT.plusHours(1));
                newerCommitted.countDown();
                return written;
            });
            Future<Integer> older = pool.submit(() -> {
                newerCommitted.await(10, TimeUnit.SECONDS);
                return upsert(1L, rows(n, 10L, EligibilityStatus.POSSIBLY_ELIGIBLE), AT.plusHours(2));
            });

            assertThat(newer.get(30, TimeUnit.SECONDS)).isEqualTo(n);
            assertThat(older.get(30, TimeUnit.SECONDS)).isZero();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT DISTINCT profile_version || ':' || eligibility_status "
                + "FROM eligibility_results", String.class)).containsExactly("2:NOT_ELIGIBLE");
    }

    @Test
    void 최신_계산이_잠금을_잡은_동안_오래된_계산은_기다렸다가_건너뛴다() throws Exception {
        int n = 200;
        upsert(1L, rows(n, 10L, EligibilityStatus.ELIGIBLE), AT);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch newerWritten = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Integer> newer = pool.submit(() -> tx.execute(s -> {
                int written = repository.upsert(USER, 2L, RULE, AT.plusHours(1),
                        rows(n, 10L, EligibilityStatus.NOT_ELIGIBLE));
                newerWritten.countDown();
                await(release);
                return written;
            }));
            assertThat(newerWritten.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> older = pool.submit(() ->
                    upsert(1L, rows(n, 10L, EligibilityStatus.POSSIBLY_ELIGIBLE), AT.plusHours(2)));
            Thread.sleep(300);
            assertThat(older.isDone()).as("커밋 전 잠긴 행을 기다린다").isFalse();
            release.countDown();

            assertThat(newer.get(30, TimeUnit.SECONDS)).isEqualTo(n);
            assertThat(older.get(30, TimeUnit.SECONDS)).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT DISTINCT profile_version || ':' || eligibility_status "
                + "FROM eligibility_results", String.class)).containsExactly("2:NOT_ELIGIBLE");
    }

    @Test
    void 묶음_하나가_실패하면_같은_트랜잭션의_앞_묶음도_저장되지_않는다() {
        List<UpsertRow> bad = List.of(new UpsertRow(9L, 10L, EligibilityStatus.ELIGIBLE, "가".repeat(501), List.of(),
                VALID_UNTIL));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            repository.upsert(USER, 1L, RULE, AT, rows(3, 10L, EligibilityStatus.ELIGIBLE));
            repository.upsert(USER, 1L, RULE, AT, bad);
        })).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eligibility_results", Long.class)).isZero();
    }

    @Test
    void 천_행을_SQL_한_번에_저장한다() {
        assertThat(upsert(1L, rows(1_000, 10L, EligibilityStatus.ELIGIBLE), AT)).isEqualTo(1_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eligibility_results", Long.class)).isEqualTo(1_000);
    }

    private int upsert(Long profileVersion, List<UpsertRow> rows, LocalDateTime at) {
        Integer written = tx.execute(s -> repository.upsert(USER, profileVersion, RULE, at, rows));
        return written == null ? 0 : written;
    }

    private static List<UpsertRow> rows(int n, Long versionId, EligibilityStatus status) {
        return LongStream.rangeClosed(1, n)
                .mapToObj(i -> new UpsertRow(i, versionId, status, "사유 " + i,
                        List.of(Map.of("type", "AGE", "result", "MATCHED", "expected", 34)), VALID_UNTIL))
                .toList();
    }

    private List<Map<String, Object>> dump() {
        return jdbc.queryForList("SELECT * FROM eligibility_results ORDER BY opportunity_id");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
