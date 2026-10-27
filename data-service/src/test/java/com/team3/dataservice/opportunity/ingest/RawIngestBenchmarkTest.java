package com.team3.dataservice.opportunity.ingest;

import com.team3.dataservice.collection.domain.entity.ExternalSource;
import com.team3.dataservice.collection.domain.entity.SyncHistory;
import com.team3.dataservice.opportunity.normalization.*;
import com.team3.dataservice.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.dataservice.opportunity.normalization.official.OfficialSupplementer;
import com.team3.dataservice.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.dataservice.opportunity.service.TrainingCourseNormalizationService;
import com.team3.dataservice.opportunity.service.YouthPolicyNormalizationService;
import com.team3.dataservice.region.directory.RegionDirectory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 수집 원본 → 정규화 연동 성능 측정 (개선 전 기준선). 결과는 build/reports/ingest-benchmark/result-*.md.
 *
 * 시나리오 (원본 건수 N마다 빈 테이블에서 시작)
 * 1. 첫 적재: 처리 기록이 없는 원본 N건을 대조(reconcile)로 모두 정규화
 * 2. 변경 없는 대조: 바로 다시 대조 (정규화 0건, 대조 조회 비용만)
 * 3. 1% 변경: N의 1% 원본에 새 스냅샷 + 정제 요청을 넣고 요청 처리(processQueue)
 *
 * 지표: 전체 시간, 초당 처리 건수, SQL 종류별 횟수·시간(JdbcTimer), 커밋 횟수·시간, DB 밖 시간(전체 − JDBC, 정규화 계산 + JPA 처리 추정),
 *       힙 최대 사용량, GC 횟수·시간.
 *
 * 실행 (홈서버, 일회용 DB): YON_BENCH_DB_URL=jdbc:postgresql://localhost:5432/yon_bench sh ./gradlew test --tests '*RawIngestBenchmarkTest'
 * 선택 환경변수: YON_BENCH_DB_USERNAME(postgres), YON_BENCH_DB_PASSWORD(1234), YON_BENCH_ENV_LABEL,
 *   YON_INGEST_BENCH_SIZES(1000,5000,10000: all이면 실데이터 원본 전체), YON_INGEST_BENCH_TX_SIZES(1,100,500,1000: 트랜잭션 1개로 묶는 원본 수, 크기마다 전 시나리오 반복),
 *   YON_INGEST_BENCH_YOUTH_PERCENT(5: 원본 중 청년정책 비율, 합성 데이터만), YON_INGEST_BENCH_BATCH_SIZE(100),
 *   YON_INGEST_BENCH_SOURCE_SCHEMA(비우면 합성 데이터. 스키마 이름을 주면 그 스키마의 raw·regions 테이블(실데이터)에서 원본·지역 사전을 복사),
 *   YON_INGEST_BENCH_FIRST_LOAD(reconcile: 대조로 첫 적재 / queue: 원본마다 PENDING 정제 요청을 넣고 요청 처리로 첫 적재 = 운영 첫 수집과 같은 경로),
 *   YON_INGEST_BENCH_REPEAT(1: 같은 조건 반복 횟수. 2 이상이면 중앙값 표 추가),
 *   YON_INGEST_BENCH_ASYNC_COMMIT(false: true면 정규화 트랜잭션만 synchronous_commit=off)
 */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "YON_BENCH_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${YON_BENCH_DB_URL}",
        "spring.datasource.username=${YON_BENCH_DB_USERNAME:postgres}",
        "spring.datasource.password=${YON_BENCH_DB_PASSWORD:1234}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "logging.level.com.team3.dataservice.opportunity.ingest=WARN",
        "yon.normalization.ingest.batch-size=${YON_INGEST_BENCH_BATCH_SIZE:100}",
        "yon.normalization.ingest.async-commit=${YON_INGEST_BENCH_ASYNC_COMMIT:false}"
})
@Import({com.team3.dataservice.opportunity.conditionset.ConditionSetIdCache.class,
        com.team3.dataservice.opportunity.service.OpportunitySourceLookup.class,RawIngestBenchmarkTest.TimedDataSourceConfig.class, com.team3.dataservice.config.PostgresJdbcTuning.class,
        RawIngestProcessor.class, RawRecordReader.class, RawRecordNormalizer.class,
        TrainingCourseNormalizationService.class, YouthPolicyNormalizationService.class,
        AgeNormalizer.class, ApplicationPeriodNormalizer.class, RegionNormalizer.class, RegionDirectory.class,
        CodeConditionNormalizer.class, IncomeNormalizer.class, YouthPolicyCodeTable.class,
        OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class RawIngestBenchmarkTest {

    @TestConfiguration
    static class TimedDataSourceConfig {
        @Bean
        static BeanPostProcessor timedDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource ds ? JdbcTimer.wrap(ds) : bean;
                }
            };
        }
    }

    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 10, 20, 9, 0, 0, 0, ZoneOffset.ofHours(9));
    /** 시·도 → 시·군·구 (5자리 행정코드 + 00000) */
    private static final String[][] REGIONS = {
            {"1100000000", "서울특별시", null}, {"1153000000", "구로구", "1100000000"}, {"1168000000", "강남구", "1100000000"},
            {"2600000000", "부산광역시", null}, {"2623000000", "부산진구", "2600000000"},
            {"4100000000", "경기도", null}, {"4165000000", "포천시", "4100000000"}, {"4163000000", "양주시", "4100000000"}};
    private static final String[] AREA_CODES = {"11530", "11680", "26230", "41650", "41630"};
    private static final String[] TARGET_CODES = {"C0061", "C0104", "C0054", "C0055", "C0061S"};

    @Autowired
    private RawIngestProcessor processor;
    @Autowired
    private RegionDirectory regionDirectory;
    @Autowired
    private com.team3.dataservice.opportunity.conditionset.ConditionSetIdCache conditionSetIds;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @PersistenceContext
    private EntityManager em;
    @Value("${yon.normalization.ingest.batch-size:100}")
    private int batchSize;
    @Value("${spring.jpa.properties.hibernate.jdbc.batch_size:(없음)}")
    private String hibernateBatchSize;
    @Value("${spring.jpa.properties.hibernate.order_inserts:false}")
    private String hibernateOrderInserts;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    private record Measured(int txSize, String scenario, int size, int run, int processed, long millis, long jdbcMillis,
                            long[] counts, long[] kindMillis, long peakHeapMb, long gcCount, long gcMillis,
                            java.util.Map<String, long[]> byTable, long[] db) {
    }

    /** DB 쪽 누적 지표: WAL 바이트, 전체 페이지 이미지(FPI) 수, 시간 체크포인트, 요청 체크포인트 (PostgreSQL 14+) */
    private long[] dbCounters() {
        try {
            return jdbc.queryForObject("""
                    SELECT w.wal_bytes::bigint, w.wal_fpi, b.checkpoints_timed, b.checkpoints_req
                    FROM pg_stat_wal w, pg_stat_bgwriter b
                    """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)});
        } catch (Exception e) {
            return new long[4];
        }
    }

    @Test
    void 연동_처리_성능을_측정한다() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
        List<Integer> sizes = Arrays.stream(env("YON_INGEST_BENCH_SIZES", "1000,5000,10000").split(","))
                .map(String::trim).map(v -> v.equals("all") ? sourceCount() : Integer.parseInt(v)).toList();
        int youthPercent = Integer.parseInt(env("YON_INGEST_BENCH_YOUTH_PERCENT", "5"));
        int repeat = Math.max(1, Integer.parseInt(env("YON_INGEST_BENCH_REPEAT", "1")));
        boolean queueFirstLoad = env("YON_INGEST_BENCH_FIRST_LOAD", "reconcile").equals("queue");
        String firstLoad = queueFirstLoad ? "1. 첫 적재 (요청 처리)" : "1. 첫 적재 (대조)";

        List<Integer> txSizes = Arrays.stream(env("YON_INGEST_BENCH_TX_SIZES", "1,100,500,1000").split(","))
                .map(String::trim).map(Integer::parseInt).toList();

        warmUp(youthPercent);
        List<Measured> results = new ArrayList<>();
        for (int txSize : txSizes) {
            processor.changeTransactionSize(txSize);
            for (int size : sizes) {
                for (int run = 1; run <= repeat; run++) {
                    long[] ids = prepare(size, youthPercent);
                    if (queueFirstLoad) {
                        addFirstLoadRequests();
                    }
                    results.add(measure(txSize, firstLoad, size, run,
                            queueFirstLoad ? processor::processQueue : processor::reconcile));
                    results.add(measure(txSize, "2. 변경 없는 대조", size, run, processor::reconcile));
                    addChanges(ids, Math.max(1, size / 100));
                    results.add(measure(txSize, "3. 1% 변경 (요청 처리)", size, run, processor::processQueue));
                }
            }
        }
        writeReport(results, youthPercent, txSizes, firstLoad, repeat);
    }

    private void warmUp(int youthPercent) {
        prepare(Math.min(200, sourceSchema() == null ? 200 : sourceCount()), youthPercent);
        processor.reconcile();
    }

    // ── 실데이터 원본 (YON_INGEST_BENCH_SOURCE_SCHEMA) ──

    /** 실데이터 스키마 이름. 비우면 null (합성 데이터) */
    private static String sourceSchema() {
        String schema = System.getenv("YON_INGEST_BENCH_SOURCE_SCHEMA");
        if (schema == null || schema.isBlank()) {
            return null;
        }
        if (!schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("YON_INGEST_BENCH_SOURCE_SCHEMA 형식이 아닙니다: " + schema);
        }
        return schema;
    }

    private int sourceCount() {
        String schema = sourceSchema();
        if (schema == null) {
            throw new IllegalStateException("all은 YON_INGEST_BENCH_SOURCE_SCHEMA를 지정해야 쓸 수 있습니다");
        }
        Integer count = new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM " + schema + ".raw", Integer.class);
        return count == null ? 0 : count;
    }

    /** 모든 원본에 PENDING 정제 요청(CREATED)을 넣는다. 운영 첫 수집과 같은 경로(NOTIFY → 요청 처리)로 첫 적재를 잰다 */
    private void addFirstLoadRequests() {
        jdbc.update("""
                INSERT INTO raw_normalization_request (raw_record_id, previous_raw_record_id, source_id, event_type, status, created_at)
                SELECT id, NULL, source_id, 'CREATED', 'PENDING', now() FROM raw_external_record ORDER BY id
                """);
        jdbc.execute("ANALYZE raw_normalization_request");
    }

    private Measured measure(int txSize, String scenario, int size, int run, java.util.function.IntSupplier action) {
        System.gc();
        ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
        long gcCount0 = gcCount();
        long gcMillis0 = gcMillis();
        long[] db0 = dbCounters();
        JdbcTimer.reset();
        long start = System.nanoTime();
        int processed = action.getAsInt();
        long millis = (System.nanoTime() - start) / 1_000_000;
        long[] db1 = dbCounters();
        long[] db = new long[4];
        for (int i = 0; i < 4; i++) {
            db[i] = db1[i] - db0[i];
        }
        JdbcTimer.Kind[] kinds = JdbcTimer.Kind.values();
        long[] counts = new long[kinds.length];
        long[] kindMillis = new long[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            counts[i] = JdbcTimer.count(kinds[i]);
            kindMillis[i] = JdbcTimer.millis(kinds[i]);
        }
        long peakHeap = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP)
                .mapToLong(p -> p.getPeakUsage().getUsed()).sum() / 1024 / 1024;
        Measured m = new Measured(txSize, scenario, size, run, processed, millis, JdbcTimer.totalMillis(), counts, kindMillis,
                peakHeap, gcCount() - gcCount0, gcMillis() - gcMillis0, JdbcTimer.byTable(), db);
        System.out.printf("[ingest-benchmark] tx=%d %s N=%d run=%d processed=%d %dms (jdbc %dms)%n",
                txSize, scenario, size, run, processed, millis, m.jdbcMillis());
        return m;
    }

    // ── 데이터 준비 ──

    /** 테이블을 비우고 원본 size건을 넣는다. @return 원본 ID 목록 */
    private long[] prepare(int size, int youthPercent) {
        jdbc.execute("""
                TRUNCATE raw_normalization_request, raw_external_record, sync_history, external_source, raw_ingest_states,
                         application_windows, opportunity_conditions, opportunity_regions, training_course_details,
                         opportunity_sources, opportunities, opportunity_versions, condition_sets, regions CASCADE
                """);
        String schema = sourceSchema();
        if (schema == null) {
            for (String[] r : REGIONS) {
                jdbc.update("INSERT INTO regions (region_code, region_name, parent_code) VALUES (?, ?, ?)", r[0], r[1], r[2]);
            }
        } else {
            // 부모가 먼저 들어가도록 상위 코드 없는 것부터
            jdbc.update("INSERT INTO regions (region_code, region_name, parent_code) SELECT region_code, region_name, parent_code FROM "
                    + schema + ".regions ORDER BY parent_code NULLS FIRST, region_code");
        }
        regionDirectory.refresh();
        // condition_sets를 비웠으므로 조건 묶음 ID 캐시도 비운다 (안 비우면 회차 2부터 지워진 ID를 쓰고 묶음 INSERT도 건너뜀)
        conditionSetIds.clear();
        long[] syncIds = new long[2];
        long[] sourceIds = new long[2];
        tx.executeWithoutResult(s -> {
            ExternalSource work24 = new ExternalSource("EMPLOYMENT24", "고용24", "https://www.work24.go.kr", true, T1);
            ExternalSource youth = new ExternalSource("YOUTH_POLICY", "온통청년", "https://www.youthcenter.go.kr", true, T1);
            em.persist(work24);
            em.persist(youth);
            SyncHistory work24Sync = new SyncHistory(work24, "BENCH", T1);
            SyncHistory youthSync = new SyncHistory(youth, "BENCH", T1);
            em.persist(work24Sync);
            em.persist(youthSync);
            em.flush();
            sourceIds[0] = work24.getId();
            sourceIds[1] = youth.getId();
            syncIds[0] = work24Sync.getId();
            syncIds[1] = youthSync.getId();
        });
        if (schema != null) {
            jdbc.update("""
                    INSERT INTO raw_external_record (source_id, sync_id, external_id, payload_hash, payload, first_seen_at, last_seen_at)
                    SELECT CASE src.source_code WHEN 'YOUTH_POLICY' THEN ? ELSE ? END,
                           CASE src.source_code WHEN 'YOUTH_POLICY' THEN ? ELSE ? END,
                           src.external_id, md5(src.payload::text), src.payload, ?, ?
                    FROM (SELECT * FROM %s.raw ORDER BY ord LIMIT ?) src
                    ORDER BY src.ord
                    """.formatted(schema), sourceIds[1], sourceIds[0], syncIds[1], syncIds[0],
                    Timestamp.valueOf(LocalDateTime.of(2026, 10, 20, 9, 0)), Timestamp.valueOf(LocalDateTime.of(2026, 10, 20, 9, 0)), size);
            analyzeSources();
            return jdbc.queryForList("SELECT id FROM raw_external_record ORDER BY id", Long.class)
                    .stream().mapToLong(Long::longValue).toArray();
        }
        int youthCount = size * youthPercent / 100;
        List<Object[]> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            boolean youth = i < youthCount;
            String externalId = youth ? "R" + i : "[\"AIG" + (i / 5) + "\",\"" + (i % 5 + 1) + "\"]";
            String payload = youth ? policy(i, 0) : course(i, 0);
            rows.add(new Object[]{sourceIds[youth ? 1 : 0], syncIds[youth ? 1 : 0], externalId,
                    "bench:" + payload.hashCode(), payload, Timestamp.valueOf(LocalDateTime.of(2026, 10, 20, 9, 0))});
        }
        jdbc.batchUpdate("""
                INSERT INTO raw_external_record (source_id, sync_id, external_id, payload_hash, payload, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, rows.stream().map(r -> new Object[]{r[0], r[1], r[2], r[3], r[4], r[5], r[5]}).toList());
        analyzeSources();
        return jdbc.queryForList("SELECT id FROM raw_external_record ORDER BY id", Long.class)
                .stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * 원본·지역 테이블만 통계를 갱신한다. 비운 정규화 테이블까지 ANALYZE하면 "행 0건" 통계가 생겨,
     * 외래 키 확인(opportunities, opportunity_versions 등을 찾는 조회)이 순차 스캔 계획으로 캐시된다.
     * 그러면 표가 커질수록 건당 확인이 느려지다가 autovacuum이 다시 ANALYZE할 때(최대 1분 주기)에야 풀려서
     * 회차마다 첫 적재 시간이 크게 흔들린다 (E7b). TRUNCATE 직후의 "통계 없음" 상태로 두면 인덱스 계획을 쓴다.
     */
    private void analyzeSources() {
        jdbc.execute("ANALYZE raw_external_record, regions");
    }

    /** 원본 count건에 새 스냅샷(내용 변경)과 정제 요청을 넣는다 (측정 밖 준비라 SQL 두 문장으로 한 번에) */
    private void addChanges(long[] ids, int count) {
        StringBuilder targets = new StringBuilder();
        for (int k = 0; k < count; k++) {
            if (k > 0) {
                targets.append(',');
            }
            targets.append(ids[(int) ((long) k * ids.length / count)]);
        }
        Long before = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM raw_external_record", Long.class);
        jdbc.update("""
                INSERT INTO raw_external_record (source_id, sync_id, external_id, payload_hash, payload, first_seen_at, last_seen_at)
                SELECT source_id, sync_id, external_id, payload_hash || ':v2',
                       jsonb_set(payload, '{title}', to_jsonb(coalesce(payload->>'title', '') || ' (변경)'))
                         || jsonb_build_object('plcyNm', coalesce(payload->>'plcyNm', '') || ' (변경)'),
                       first_seen_at, last_seen_at + interval '1 day'
                FROM raw_external_record WHERE id IN (%s) ORDER BY id
                """.formatted(targets));
        jdbc.update("""
                INSERT INTO raw_normalization_request (raw_record_id, previous_raw_record_id, source_id, event_type, status, created_at)
                SELECT n.id, o.id, n.source_id, 'UPDATED', 'PENDING', now()
                FROM raw_external_record n
                JOIN raw_external_record o ON o.source_id = n.source_id AND o.external_id = n.external_id AND o.id IN (%s)
                WHERE n.id > ?
                ORDER BY n.id
                """.formatted(targets), before);
        jdbc.execute("ANALYZE");
    }

    private static String course(int i, int rev) {
        return """
                {"trprId":"AIG%d","trprDegr":"%d","title":"훈련과정 %d","subTitle":"훈련기관 %d","instCd":"2020%05d",
                 "trainstCstId":"5000%08d","traStartDate":"2026-11-%02d","traEndDate":"2027-04-30","realMan":"%d","courseMan":"%d",
                 "yardMan":"20","ncsCd":"20010202","trainTargetCd":"%s","trainTarget":"훈련유형","wkendSe":"3",
                 "trngAreaCd":"%s","address":"서울 구로구","telNo":"02-000-0000","regCourseMan":"3","stdgScor":"0",
                 "titleLink":"https://www.work24.go.kr/hr/a/a/3100/selectTracseDetl.do?tracseId=AIG%d&tracseTme=%d"}
                """.formatted(i / 5, i % 5 + 1, i, i / 5, i % 100000, i, i % 28 + 1, 1_000_000 + i, 100_000 + i,
                TARGET_CODES[i % TARGET_CODES.length], AREA_CODES[i % AREA_CODES.length], i / 5, i % 5 + 1, rev);
    }

    private static String policy(int i, int rev) {
        return """
                {"plcyNo":"R%d","plcyNm":"청년 지원 정책 %d","sprtTrgtMinAge":"19","sprtTrgtMaxAge":"%d","sprtTrgtAgeLmtYn":"N",
                 "ptcpPrpTrgtCn":"기초생활수급자 제외","addAplyQlfcCndCn":"공고일 기준 해당 지역에 거주하는 무주택 청년",
                 "aplyPrdSeCd":"0057001","aplyYmd":"20261001 ~ 20261031","plcyExplnCn":"청년의 주거비 부담을 덜기 위한 지원",
                 "earnEtcCn":"가구 기준중위소득 150%% 이하","etcMttrCn":"","lclsfNm":"주거","mclsfNm":"주거지원",
                 "zipCd":"11530,11680","mrgSttsCd":"0055003","jobCd":"0013010","schoolCd":"0049010","plcyMajorCd":"0011009",
                 "sbizCd":"0014010","earnCndSeCd":"0043003","earnMinAmt":"0","earnMaxAmt":"0","frstRegDt":"2026-09-01 10:00:00"}
                """.formatted(i, i, 34 + i % 6);
    }

    // ── 보고서 ──

    private void writeReport(List<Measured> results, int youthPercent, List<Integer> txSizes, String firstLoad, int repeat) throws Exception {
        StringBuilder sb = new StringBuilder("# 수집 원본 → 정규화 연동 벤치마크\n\n");
        sb.append("측정 시각: ").append(LocalDateTime.now()).append("\n\n## 측정 환경\n\n| 항목 | 값 |\n|---|---|\n");
        row(sb, "환경 이름 (YON_BENCH_ENV_LABEL)", System.getenv().getOrDefault("YON_BENCH_ENV_LABEL", "(지정 안 함)"));
        row(sb, "호스트 이름", hostName());
        row(sb, "CPU 모델", cpuModel());
        row(sb, "전체 메모리", totalMemory());
        row(sb, "JVM 최대 힙", Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB");
        row(sb, "PostgreSQL", show("server_version") + " (shared_buffers=" + show("shared_buffers")
                + ", work_mem=" + show("work_mem") + ", synchronous_commit=" + show("synchronous_commit") + ")");
        row(sb, "PostgreSQL WAL", "wal_buffers=" + show("wal_buffers") + ", max_wal_size=" + show("max_wal_size")
                + ", checkpoint_completion_target=" + show("checkpoint_completion_target")
                + ", checkpoint_timeout=" + show("checkpoint_timeout") + ", wal_compression=" + show("wal_compression")
                + ", full_page_writes=" + show("full_page_writes"));
        row(sb, "JDBC URL 옵션", jdbcOptions());
        row(sb, "JDBC 드라이버 옵션 (Hikari)", hikariProperties());
        row(sb, "Hibernate 묶음 (jdbc.batch_size, order_inserts)", hibernateBatchSize + ", " + hibernateOrderInserts);
        row(sb, "원본 데이터", sourceSchema() == null ? "합성 (청년정책 " + youthPercent + "%)" : "실데이터 (" + sourceSchema() + ".raw, 청년정책 " + sourceYouthPercent() + ")");
        row(sb, "첫 적재 경로", firstLoad);
        row(sb, "정규화 트랜잭션 비동기 커밋 (async-commit)", env("YON_INGEST_BENCH_ASYNC_COMMIT", "false"));
        row(sb, "반복", repeat + "회");
        row(sb, "처리 묶음 크기 (yon.normalization.ingest.batch-size)", String.valueOf(batchSize));
        row(sb, "트랜잭션 크기 (비교 대상)", txSizes + " (원본 N건 = REQUIRES_NEW 1개, 묶음 끝에 flush·clear)");
        row(sb, "대조 조회", "id 커서 + NOT EXISTS(더 새 원본) 순회");

        sb.append("\n## 처리 시간\n\n| 트랜잭션 크기 | 시나리오 | 원본 N | 회차 | 처리 건수 | 전체(ms) | 건당(ms) | 초당 건수 | JDBC(ms) | JDBC 비중 | DB 밖(ms, 추정) |\n")
                .append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Measured m : results) {
            double perItem = m.processed() == 0 ? 0 : (double) m.millis() / m.processed();
            double perSecond = m.millis() == 0 ? 0 : m.processed() * 1000.0 / m.millis();
            double share = m.millis() == 0 ? 0 : m.jdbcMillis() * 100.0 / m.millis();
            sb.append(String.format("| %d | %s | %d | %d | %d | %d | %.2f | %.0f | %d | %.0f%% | %d |%n", m.txSize(), m.scenario(), m.size(),
                    m.run(), m.processed(), m.millis(), perItem, perSecond, m.jdbcMillis(), share, m.millis() - m.jdbcMillis()));
        }

        if (repeat > 1) {
            sb.append("\n## 중앙값 (반복 ").append(repeat).append("회)\n\n| 트랜잭션 크기 | 시나리오 | 원본 N | 전체(ms) 중앙값 | 최소 | 최대 | 건당(ms) | 초당 건수 |\n")
                    .append("|---:|---|---:|---:|---:|---:|---:|---:|\n");
            results.stream().collect(java.util.stream.Collectors.groupingBy(
                            m -> m.txSize() + "|" + m.scenario() + "|" + m.size(), java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()))
                    .forEach((key, group) -> {
                        long[] ms = group.stream().mapToLong(Measured::millis).sorted().toArray();
                        long median = ms[ms.length / 2];
                        Measured first = group.getFirst();
                        double perItem = first.processed() == 0 ? 0 : (double) median / first.processed();
                        double perSecond = median == 0 ? 0 : first.processed() * 1000.0 / median;
                        sb.append(String.format("| %d | %s | %d | %d | %d | %d | %.2f | %.0f |%n", first.txSize(), first.scenario(),
                                first.size(), median, ms[0], ms[ms.length - 1], perItem, perSecond));
                    });
        }

        sb.append("\n## 첫 적재 테이블별 SQL (첫 회차, 시간 큰 순 15개, 괄호: 누적 ms, 처리 1건당 횟수)\n");
        for (Measured m : results) {
            if (!m.scenario().startsWith("1.") || m.run() != 1) {
                continue;
            }
            sb.append(String.format("%n### 트랜잭션 %d, N=%d%n%n| SQL | 횟수 | 누적(ms) | 1건당 |%n|---|---:|---:|---:|%n", m.txSize(), m.size()));
            m.byTable().entrySet().stream().limit(15).forEach(e -> sb.append(String.format("| %s | %d | %d | %.2f |%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1], m.processed() == 0 ? 0 : (double) e.getValue()[0] / m.processed())));
        }

        sb.append("\n## SQL 종류별 횟수 (괄호: 누적 ms)\n\n| 트랜잭션 크기 | 시나리오 | 원본 N | SELECT | INSERT | UPDATE | DELETE | 기타 | COMMIT | 처리 1건당 SQL |\n")
                .append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Measured m : results) {
            long sqlTotal = 0;
            StringBuilder cells = new StringBuilder();
            for (int i = 0; i < m.counts().length; i++) {
                cells.append(String.format(" %d (%d) |", m.counts()[i], m.kindMillis()[i]));
                if (i < m.counts().length - 1) {
                    sqlTotal += m.counts()[i];
                }
            }
            String perItem = m.processed() == 0 ? "-" : String.format("%.1f", (double) sqlTotal / m.processed());
            sb.append(String.format("| %d | %s | %d |%s %s |%n", m.txSize(), m.scenario(), m.size(), cells, perItem));
        }

        sb.append("\n## DB 쓰기 (WAL·체크포인트, 측정 구간 동안 늘어난 양)\n\n| 트랜잭션 크기 | 시나리오 | 원본 N | 회차 | WAL(MB) | 전체 페이지 이미지 | 체크포인트(시간) | 체크포인트(요청) |\n")
                .append("|---:|---|---:|---:|---:|---:|---:|---:|\n");
        for (Measured m : results) {
            sb.append(String.format("| %d | %s | %d | %d | %d | %d | %d | %d |%n", m.txSize(), m.scenario(), m.size(), m.run(),
                    m.db()[0] / 1024 / 1024, m.db()[1], m.db()[2], m.db()[3]));
        }

        sb.append("\n## JVM\n\n| 트랜잭션 크기 | 시나리오 | 원본 N | 힙 최대(MB) | GC 횟수 | GC 시간(ms) |\n|---:|---|---:|---:|---:|---:|\n");
        for (Measured m : results) {
            sb.append(String.format("| %d | %s | %d | %d | %d | %d |%n", m.txSize(), m.scenario(), m.size(), m.peakHeapMb(), m.gcCount(), m.gcMillis()));
        }
        sb.append("""

                ## 읽는 법
                - JDBC(ms): 드라이버가 SQL·커밋을 보내고 응답받을 때까지의 합 (네트워크 + DB 실행). ResultSet 읽기는 제외
                - DB 밖(ms) = 전체 − JDBC. 정규화 계산, JSON 변환, Hibernate 처리(엔티티·더티 체크 등)가 섞인 추정값
                - 처리 1건당 SQL: 커밋 제외 SQL 수 ÷ 처리 건수
                - 1번은 처음 한 번만 겪는 첫 적재, 2·3번이 평상시 비용
                """);

        Path dir = Path.of("build", "reports", "ingest-benchmark");
        Files.createDirectories(dir);
        Path file = dir.resolve("result-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(file, sb.toString());
        System.out.println(sb);
        System.out.println("[ingest-benchmark] report: " + file.toAbsolutePath());
    }

    private String sourceYouthPercent() {
        try {
            Double percent = jdbc.queryForObject("SELECT round(100.0 * count(*) FILTER (WHERE source_code = 'YOUTH_POLICY') / count(*), 1) FROM "
                    + sourceSchema() + ".raw", Double.class);
            return percent + "%";
        } catch (Exception e) {
            return "?";
        }
    }

    private String hikariProperties() {
        try {
            com.zaxxer.hikari.HikariDataSource hikari = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
            return hikari.getDataSourceProperties().isEmpty() ? "(없음)" : hikari.getDataSourceProperties().toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private String jdbcOptions() {
        try (var c = dataSource.getConnection()) {
            String url = c.getMetaData().getURL();
            int q = url.indexOf('?');
            return q < 0 ? "(없음)" : url.substring(q + 1);
        } catch (Exception e) {
            return "?";
        }
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("| ").append(k).append(" | ").append(v).append(" |\n");
    }

    private String show(String name) {
        try {
            return jdbc.queryForObject("SHOW " + name, String.class);
        } catch (Exception e) {
            return "?";
        }
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "알 수 없음";
        }
    }

    private static String cpuModel() {
        try {
            return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
                    .filter(l -> l.startsWith("model name"))
                    .map(l -> l.substring(l.indexOf(':') + 1).trim())
                    .findFirst().orElse("알 수 없음");
        } catch (Exception e) {
            return "알 수 없음 (" + System.getProperty("os.name") + ")";
        }
    }

    private static String totalMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return String.format("%.1f GB", os.getTotalMemorySize() / 1024.0 / 1024 / 1024);
        }
        return "알 수 없음";
    }
}
