package com.team3.backend.eligibility.benchmark;

import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.eligibility.engine.EligibilityProfileInput;
import com.team3.backend.eligibility.benchmark.BenchmarkData.Complexity;
import com.team3.backend.eligibility.benchmark.BenchmarkData.ProfileKind;
import com.team3.backend.eligibility.benchmark.BenchmarkData.Template;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.dto.EligibilityListResponse;
import com.team3.backend.eligibility.engine.EligibilityConditionInput;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.eligibility.service.EligibilityQueryService;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.normalization.*;
import com.team3.backend.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.backend.opportunity.normalization.official.OfficialSupplementer;
import com.team3.backend.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.backend.opportunity.service.YouthPolicyNormalizationService;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.region.domain.repository.RegionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판정 성능 병목 분석 (운영 코드 변경 없음).
 *
 * 실행: YON_BENCH_DB_URL=jdbc:postgresql://localhost:5432/yon_bench \
 *       sh ./gradlew cleanTest test --tests '*EligibilityBottleneckBenchmarkTest'
 * 선택: YON_BENCH_SIZES, YON_BENCH_WARMUP(10), YON_BENCH_ITERATIONS(50), YON_BENCH_FULL_WARMUP(3),
 *       YON_BENCH_FULL_ITERATIONS(20), YON_BENCH_EXPLAIN_REPEAT(5)
 * 결과: build/reports/eligibility-benchmark/bottleneck-*.md
 *
 * A. 쿼리 감사: Hibernate가 준비한 SQL을 StatementInspector(SqlCapture)로 모두 기록한다.
 * B. 조회 비교: 현재 엔티티 경로 vs 판정에 필요한 컬럼만 읽는 JPQL 스칼라 Projection 경로 (벤치마크 전용).
 *    두 경로의 판정 결과(최종·신청 상태·조건별 결과)가 같은지 검증한다.
 * C. 구간 분리: 같은 SQL을 (1) EXPLAIN ANALYZE 서버 실행 시간, (2) 순수 JDBC 실행+전송+읽기, (3) Hibernate 경로로 실행해 비교한다.
 * D. 후보 축소 SQL: 지역·연령으로 확실히 불충족인 정책만 빼는 SQL을 만들고,
 *    빠진 정책이 전부 상세 판정 NOT_ELIGIBLE인지 프로필 7종으로 검증한다. (설계·SQL 검증만, 종단 성능 비교는 하지 않음)
 * 측정 순서 영향을 줄이기 위해 경로들을 회차마다 순서를 돌려 교차 실행하고, 시작 전에 JVM 전체 워밍업을 한다.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "YON_BENCH_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${YON_BENCH_DB_URL}",
        "spring.datasource.username=${YON_BENCH_DB_USERNAME:postgres}",
        "spring.datasource.password=${YON_BENCH_DB_PASSWORD:1234}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.team3.backend.eligibility.benchmark.SqlCapture",
        "logging.level.org.hibernate.SQL=OFF"
})
@Import({EligibilityQueryService.class, EligibilityEvaluator.class, EligibilityEngine.class, ApplicationStatusCalculator.class,
        YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class, IncomeNormalizer.class,
        YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class EligibilityBottleneckBenchmarkTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final int CHUNK = 1_000;
    private static final int[] PAGE_SIZES = {20, 100};

    // 현재 엔티티 경로 (기존 벤치마크와 같은 청크 조회)
    private static final String ENTITY_CHUNK_JPQL =
            "select o from Opportunity o join fetch o.currentVersion where o.id < :lastId order by o.id desc";
    // Projection 경로: 판정에 쓰는 컬럼만 (스냅샷·evidence·created_at 제외)
    private static final String PROJ_CHUNK_JPQL =
            "select o.id, o.currentVersion.id from Opportunity o "
                    + "where o.currentVersion is not null and o.id < :lastId order by o.id desc";
    private static final String PROJ_PAGE_JPQL =
            "select o.id, o.currentVersion.id, o.opportunityType, o.title, o.category from Opportunity o "
                    + "where o.currentVersion is not null order by o.id desc";
    private static final String PROJ_COUNT_JPQL = "select count(o) from Opportunity o where o.currentVersion is not null";
    private static final String PROJ_COND_JPQL =
            "select c.version.id, c.conditionType, c.requirementType, c.conditionField, c.expression, c.validationStatus "
                    + "from OpportunityCondition c where c.version.id in :ids";
    private static final String PROJ_REGION_JPQL =
            "select r.version.id, r.region.regionCode from OpportunityRegion r where r.version.id in :ids and r.regionRole = :role";
    private static final String PROJ_WINDOW_JPQL =
            "select w.version.id, w.periodType, w.startDate, w.endDate, w.validationStatus, w.periodText "
                    + "from ApplicationWindow w where w.version.id in :ids";

    /**
     * 후보 축소 SQL. 상세 판정에서 확실히 NOT_ELIGIBLE이 되는 경우만 뺀다.
     * - 지역: REQUIRED·VALID·status=SPECIFIC이고 거주 지역 행이 있으며, 사용자 거주지와 그 상위 지역이 하나도 없을 때
     * - 연령: REQUIRED·VALID·status가 RANGE/MIN_ONLY/MAX_ONLY이고 만 나이가 min 미만 또는 max 초과일 때
     * 그 외(NATIONAL, UNKNOWN, NEEDS_REVIEW, DATA_CONFLICT, 지역 행 없음, DATE_BOUND, 사용자 값 없음)는 모두 남긴다.
     */
    private static final String CANDIDATE_SQL = """
            WITH p AS (SELECT ?::int AS age, ?::text[] AS chain, ?::boolean AS has_region)
            SELECT o.id FROM opportunities o, p
            WHERE o.current_version_id IS NOT NULL
              AND NOT EXISTS (
                SELECT 1 FROM opportunity_conditions c
                WHERE c.version_id = o.current_version_id
                  AND c.condition_type = 'REGION' AND c.requirement_type = 'REQUIRED'
                  AND c.validation_status = 'VALID' AND c.expression->>'status' = 'SPECIFIC'
                  AND p.has_region
                  AND EXISTS (SELECT 1 FROM opportunity_regions r
                              WHERE r.version_id = c.version_id AND r.region_role = 'RESIDENCE')
                  AND NOT EXISTS (SELECT 1 FROM opportunity_regions r
                                  WHERE r.version_id = c.version_id AND r.region_role = 'RESIDENCE'
                                    AND r.region_code = ANY(p.chain)))
              AND NOT EXISTS (
                SELECT 1 FROM opportunity_conditions c
                WHERE c.version_id = o.current_version_id
                  AND c.condition_type = 'AGE' AND c.requirement_type = 'REQUIRED'
                  AND c.validation_status = 'VALID'
                  AND c.expression->>'status' IN ('RANGE', 'MIN_ONLY', 'MAX_ONLY')
                  AND p.age IS NOT NULL
                  AND ((CASE WHEN c.expression->>'min' ~ '^[0-9]+$' THEN (c.expression->>'min')::int END) > p.age
                    OR (CASE WHEN c.expression->>'max' ~ '^[0-9]+$' THEN (c.expression->>'max')::int END) < p.age))
            """;

    @Autowired private EligibilityQueryService queryService;
    @Autowired private EligibilityEngine engine;
    @Autowired private ApplicationStatusCalculator applicationStatusCalculator;
    @Autowired private YouthPolicyNormalizationService normalizationService;
    @Autowired private RegionDirectory regionDirectory;
    @Autowired private RegionRepository regionRepository;
    @Autowired private EligibilityProfileRepository profileRepository;
    @Autowired private OpportunityConditionRepository conditionRepository;
    @Autowired private OpportunityRegionRepository opportunityRegionRepository;
    @Autowired private ApplicationWindowRepository windowRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private JdbcTemplate jdbc;
    private TransactionTemplate readOnlyTx;
    private Statistics statistics;
    private BenchmarkData data;
    private final List<String> failures = new ArrayList<>();

    // ───────────────────────── 결과 구조 ─────────────────────────

    private record Stat(double p50, double p95) {
        static Stat of(List<Double> millis) {
            List<Double> s = new ArrayList<>(millis);
            Collections.sort(s);
            return new Stat(rank(s, 0.50), rank(s, 0.95));
        }

        private static double rank(List<Double> sorted, double p) {
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }

    /** 한 번의 전체 판정(또는 JDBC 읽기) 실행 결과 */
    private static final class Run {
        long profileNanos, queryNanos, mappingNanos, engineNanos;
        long queries, engineQueries;
        int evaluated;
        Map<Long, String> judged;
        Map<EligibilityStatus, Long> distribution = new EnumMap<>(EligibilityStatus.class);
    }

    private enum FullPath { ENTITY, PROJECTION, JDBC_ENTITY_SQL, JDBC_PROJECTION_SQL }

    private record SqlSet(String chunk, String conditions, String regions, String windows) {
    }

    private record Explain(double planning, double execution, String topBuffers) {
    }

    private record ChunkKey(long lastId, List<Long> versionIds) {
    }

    private record AuditRow(int size, Complexity complexity, ProfileKind profile, String page, int queries) {
    }

    private record ListRow(int size, Complexity complexity, int pageSize, String page, Stat service, Stat projection,
                           long serviceQueries, long projectionQueries) {
    }

    private record FullRow(int size, Complexity complexity, Map<FullPath, Stat[]> stats, Map<FullPath, Long> queries,
                           long entityEngineQueries, double explainEntity, double explainProjection,
                           double planningEntity, double planningProjection, String buffersEntity, String buffersProjection) {
    }

    private record CandidateRow(int size, Complexity complexity, ProfileKind profile, int total, int candidates,
                                int excluded, long notEligible, int unsafe, Stat sql) {
    }

    // ───────────────────────── 실행 ─────────────────────────

    @Test
    void 판정_병목_분석() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        readOnlyTx = new TransactionTemplate(transactionManager);
        readOnlyTx.setReadOnly(true);
        TransactionTemplate writeTx = new TransactionTemplate(transactionManager);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        data = new BenchmarkData(jdbc, writeTx, regionRepository, regionDirectory, profileRepository, normalizationService);

        List<Integer> sizes = intList("YON_BENCH_SIZES", "1000,5000,10000");
        int warmup = intEnv("YON_BENCH_WARMUP", 10);
        int iterations = intEnv("YON_BENCH_ITERATIONS", 50);
        int fullWarmup = intEnv("YON_BENCH_FULL_WARMUP", 3);
        int fullIterations = intEnv("YON_BENCH_FULL_ITERATIONS", 20);
        int explainRepeat = intEnv("YON_BENCH_EXPLAIN_REPEAT", 5);

        data.seedRegions();
        data.seedProfiles();
        Map<String, Template> templates = data.normalizeTemplates();
        data.verifyTemplateCharacteristics(templates);

        // JVM 전체 워밍업 (기록하지 않음): 1,000건 MIXED로 모든 경로를 충분히 실행
        prepare(templates, Complexity.MIXED, 1000);
        for (int i = 0; i < 30; i++) {
            for (FullPath p : FullPath.values()) {
                runFull(p, ProfileKind.FULL.userId(), false);
            }
            queryService.evaluateAll(ProfileKind.FULL.userId(), 0, 20, NOW);
            projectionList(ProfileKind.FULL.userId(), 0, 20);
        }

        List<AuditRow> audits = new ArrayList<>();
        List<ListRow> lists = new ArrayList<>();
        List<FullRow> fulls = new ArrayList<>();
        List<CandidateRow> candidates = new ArrayList<>();
        Map<String, List<String>> sqlSamples = new LinkedHashMap<>();
        List<String> plans = new ArrayList<>();

        for (int size : sizes) {
            for (Complexity complexity : Complexity.values()) {
                prepare(templates, complexity, size);
                boolean appendix = size == sizes.get(sizes.size() - 1) && complexity == Complexity.MIXED;

                auditQueries(size, complexity, audits, appendix ? sqlSamples : null);
                verifyProjectionMatches(size, complexity);
                for (int pageSize : PAGE_SIZES) {
                    int lastPage = (size + pageSize - 1) / pageSize - 1;
                    lists.add(measureList(size, complexity, pageSize, "first", 0, warmup, iterations));
                    lists.add(measureList(size, complexity, pageSize, "last", lastPage, warmup, iterations));
                }
                fulls.add(measureFull(size, complexity, fullWarmup, fullIterations, explainRepeat, appendix ? plans : null));
                candidates.addAll(checkCandidates(size, complexity, appendix ? plans : null));
                if (appendix) {
                    explainListPages(size, plans);
                }
                System.out.printf("[bottleneck] N=%d %s done%n", size, complexity);
            }
        }
        data.clearClones();

        String report = report(audits, lists, fulls, candidates, sqlSamples, plans,
                warmup, iterations, fullWarmup, fullIterations, explainRepeat);
        Path dir = Path.of("build", "reports", "eligibility-benchmark");
        Files.createDirectories(dir);
        Path file = dir.resolve("bottleneck-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(file, report);
        System.out.println("[bottleneck] report: " + file.toAbsolutePath());
        assertThat(failures).as("검증 실패 (보고서 하단 참고)").isEmpty();
    }

    private void prepare(Map<String, Template> templates, Complexity complexity, int size) {
        data.clearClones();
        Map<String, List<Long>> ids = data.seedClones(templates, complexity, size);
        data.verifyClones(templates, ids, size);
        jdbc.execute("VACUUM ANALYZE");
        chunkLastIds.clear();
        currentChunks = chunkKeys();
    }

    // ───────────────────────── A. 쿼리 감사 ─────────────────────────

    private void auditQueries(int size, Complexity complexity, List<AuditRow> audits, Map<String, List<String>> samples) {
        int lastPage = (size + 20 - 1) / 20 - 1;
        for (ProfileKind kind : ProfileKind.values()) {
            for (int page : new int[]{0, lastPage}) {
                SqlCapture.start();
                queryService.evaluateAll(kind.userId(), page, 20, NOW);
                List<String> sqls = SqlCapture.stop();
                String label = page == 0 ? "first" : "last";
                audits.add(new AuditRow(size, complexity, kind, label, sqls.size()));
                if (samples != null && page == 0) {
                    samples.put("evaluateAll size=20 first / " + kind, sqls);
                }
            }
        }
        if (samples != null) {
            SqlCapture.start();
            runFull(FullPath.ENTITY, ProfileKind.FULL.userId(), false);
            samples.put("전체 판정 ENTITY 경로 / FULL", SqlCapture.stop());
            SqlCapture.start();
            runFull(FullPath.PROJECTION, ProfileKind.FULL.userId(), false);
            samples.put("전체 판정 PROJECTION 경로 / FULL", SqlCapture.stop());
        }
    }

    // ───────────────────────── B. 목록 판정 비교 ─────────────────────────

    private ListRow measureList(int size, Complexity complexity, int pageSize, String label, int page, int warmup, int iterations) {
        long userId = ProfileKind.FULL.userId();
        // 결과 동일성
        EligibilityListResponse expected = queryService.evaluateAll(userId, page, pageSize, NOW);
        List<String> actual = projectionList(userId, page, pageSize);
        List<String> expectedItems = expected.items().stream()
                .map(i -> i.opportunityId() + "|" + i.opportunityVersionId() + "|" + i.opportunityType() + "|" + i.title()
                        + "|" + i.category() + "|" + i.eligibility() + "|" + i.applicationStatus() + "|" + i.reason())
                .toList();
        if (!expectedItems.equals(actual)) {
            failures.add("목록 Projection 결과 불일치: N=" + size + " " + complexity + " size=" + pageSize + " " + label);
        }
        for (int i = 0; i < warmup; i++) {
            queryService.evaluateAll(userId, page, pageSize, NOW);
            projectionList(userId, page, pageSize);
        }
        List<Double> service = new ArrayList<>();
        List<Double> projection = new ArrayList<>();
        long serviceQueries = 0;
        long projectionQueries = 0;
        for (int i = 0; i < iterations; i++) {
            boolean serviceFirst = i % 2 == 0;
            for (int k = 0; k < 2; k++) {
                boolean runService = (k == 0) == serviceFirst;
                long q0 = statistics.getPrepareStatementCount();
                long t0 = System.nanoTime();
                if (runService) {
                    queryService.evaluateAll(userId, page, pageSize, NOW);
                } else {
                    projectionList(userId, page, pageSize);
                }
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                long q = statistics.getPrepareStatementCount() - q0;
                if (runService) {
                    service.add(ms);
                    serviceQueries = q;
                } else {
                    projection.add(ms);
                    projectionQueries = q;
                }
            }
        }
        return new ListRow(size, complexity, pageSize, label, Stat.of(service), Stat.of(projection), serviceQueries, projectionQueries);
    }

    /** 목록 판정 Projection 경로 (서비스와 같은 절차, 조회만 Projection) */
    private List<String> projectionList(long userId, int page, int pageSize) {
        return readOnlyTx.execute(s -> {
            EligibilityProfile profile = profileRepository.findByUserId(userId).orElseThrow();
            List<Object[]> rows = em.createQuery(PROJ_PAGE_JPQL, Object[].class)
                    .setFirstResult(page * pageSize).setMaxResults(pageSize).getResultList();
            em.createQuery(PROJ_COUNT_JPQL, Long.class).getSingleResult();
            List<Long> versionIds = rows.stream().map(r -> (Long) r[1]).toList();
            Loaded loaded = loadProjection(versionIds);
            Mapped mapped = mapProjection(loaded);
            RegionIndex index = regionDirectory.index();
            EligibilityProfileInput input = EligibilityProfileInput.from(profile);
            List<String> items = new ArrayList<>();
            for (Object[] r : rows) {
                Long versionId = (Long) r[1];
                EligibilityEvaluation evaluation = engine.evaluate(input, mapped.conditions.getOrDefault(versionId, List.of()),
                        mapped.residence.getOrDefault(versionId, List.of()), index, TODAY);
                ApplicationStatus app = applicationStatusCalculator.calculate(mapped.periods.getOrDefault(versionId, List.of()), TODAY);
                items.add(r[0] + "|" + versionId + "|" + r[2] + "|" + r[3] + "|" + r[4] + "|" + evaluation.status()
                        + "|" + app + "|" + evaluation.reason());
            }
            return items;
        });
    }

    private record Loaded(List<Object[]> conditions, List<Object[]> regions, List<Object[]> windows) {
    }

    private record Mapped(Map<Long, List<EligibilityConditionInput>> conditions, Map<Long, List<String>> residence,
                          Map<Long, List<ApplicationPeriod>> periods) {
    }

    private Loaded loadProjection(List<Long> versionIds) {
        return new Loaded(
                em.createQuery(PROJ_COND_JPQL, Object[].class).setParameter("ids", versionIds).getResultList(),
                em.createQuery(PROJ_REGION_JPQL, Object[].class).setParameter("ids", versionIds)
                        .setParameter("role", RegionRole.RESIDENCE).getResultList(),
                em.createQuery(PROJ_WINDOW_JPQL, Object[].class).setParameter("ids", versionIds).getResultList());
    }

    @SuppressWarnings("unchecked")
    private static Mapped mapProjection(Loaded loaded) {
        Map<Long, List<EligibilityConditionInput>> conditions = new HashMap<>();
        for (Object[] r : loaded.conditions()) {
            conditions.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add(new EligibilityConditionInput(
                    (ConditionType) r[1], (RequirementType) r[2], (String) r[3],
                    (Map<String, Object>) r[4], (ValidationStatus) r[5]));
        }
        Map<Long, List<String>> residence = new HashMap<>();
        for (Object[] r : loaded.regions()) {
            residence.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add((String) r[1]);
        }
        Map<Long, List<ApplicationPeriod>> periods = new HashMap<>();
        for (Object[] r : loaded.windows()) {
            periods.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add(new ApplicationPeriod(
                    (PeriodType) r[1], (LocalDate) r[2], (LocalDate) r[3], (ValidationStatus) r[4], (String) r[5]));
        }
        return new Mapped(conditions, residence, periods);
    }

    /** 전체 판정에서 ENTITY와 PROJECTION의 판정 결과가 같은지 (프로필 7종) */
    private void verifyProjectionMatches(int size, Complexity complexity) {
        for (ProfileKind kind : ProfileKind.values()) {
            Run entity = runFull(FullPath.ENTITY, kind.userId(), true);
            Run projection = runFull(FullPath.PROJECTION, kind.userId(), true);
            if (entity.evaluated != size || !entity.judged.equals(projection.judged)) {
                failures.add("전체 판정 Projection 결과 불일치: N=" + size + " " + complexity + " " + kind);
            }
        }
    }

    // ───────────────────────── B·C. 전체 판정 ─────────────────────────

    private FullRow measureFull(int size, Complexity complexity, int warmup, int iterations, int explainRepeat, List<String> plans) {
        long userId = ProfileKind.FULL.userId();
        FullPath[] paths = FullPath.values();
        for (int i = 0; i < warmup; i++) {
            for (FullPath p : paths) {
                runFull(p, userId, false);
            }
        }
        Map<FullPath, List<Run>> runs = new EnumMap<>(FullPath.class);
        for (int i = 0; i < iterations; i++) {
            for (int k = 0; k < paths.length; k++) {
                FullPath p = paths[(i + k) % paths.length];   // 회차마다 순서를 돌린다
                runs.computeIfAbsent(p, x -> new ArrayList<>()).add(runFull(p, userId, false));
            }
        }
        Map<FullPath, Stat[]> stats = new EnumMap<>(FullPath.class);
        Map<FullPath, Long> queries = new EnumMap<>(FullPath.class);
        long engineQueries = 0;
        for (FullPath p : paths) {
            List<Run> rs = runs.get(p);
            stats.put(p, new Stat[]{
                    Stat.of(rs.stream().map(r -> r.queryNanos / 1e6).toList()),
                    Stat.of(rs.stream().map(r -> r.mappingNanos / 1e6).toList()),
                    Stat.of(rs.stream().map(r -> r.engineNanos / 1e6).toList()),
                    Stat.of(rs.stream().map(r -> (r.queryNanos + r.mappingNanos + r.engineNanos) / 1e6).toList()),
                    Stat.of(rs.stream().map(r -> r.profileNanos / 1e6).toList())});
            queries.put(p, rs.get(rs.size() - 1).queries);
            if (p == FullPath.ENTITY) {
                engineQueries = rs.stream().mapToLong(r -> r.engineQueries).max().orElse(0);
            }
        }

        // C. EXPLAIN ANALYZE: 같은 SQL의 서버 실행 시간 (청크 합계, 반복 중앙값)
        SqlSet entitySql = sqlSet(FullPath.ENTITY);
        SqlSet projectionSql = sqlSet(FullPath.PROJECTION);
        List<ChunkKey> chunks = currentChunks;
        double[] entityExplain = explainFull(entitySql, chunks, false, explainRepeat);
        double[] projectionExplain = explainFull(projectionSql, chunks, true, explainRepeat);
        String entityBuffers = explainOnce(entitySql.conditions(), chunks.get(0), false, false).topBuffers();
        String projectionBuffers = explainOnce(projectionSql.conditions(), chunks.get(0), false, false).topBuffers();
        if (plans != null) {
            plans.add(plan("ENTITY 청크 조회 (첫 청크)", entitySql.chunk(), chunks.get(0), false, true));
            plans.add(plan("ENTITY 조건 조회 (첫 청크)", entitySql.conditions(), chunks.get(0), false, false));
            plans.add(plan("PROJECTION 청크 조회 (첫 청크)", projectionSql.chunk(), chunks.get(0), false, true));
            plans.add(plan("PROJECTION 조건 조회 (첫 청크)", projectionSql.conditions(), chunks.get(0), false, false));
        }
        return new FullRow(size, complexity, stats, queries, engineQueries,
                entityExplain[1], projectionExplain[1], entityExplain[0], projectionExplain[0], entityBuffers, projectionBuffers);
    }

    private Run runFull(FullPath path, long userId, boolean collect) {
        return switch (path) {
            case ENTITY -> runEntity(userId, collect);
            case PROJECTION -> runProjection(userId, collect);
            case JDBC_ENTITY_SQL -> runJdbc(sqlSet(FullPath.ENTITY), false);
            case JDBC_PROJECTION_SQL -> runJdbc(sqlSet(FullPath.PROJECTION), true);
        };
    }

    /** 현재 엔티티 경로 (기존 벤치마크·운영 서비스와 같은 조회) */
    private Run runEntity(long userId, boolean collect) {
        Run run = new Run();
        if (collect) {
            run.judged = new HashMap<>();
        }
        readOnlyTx.executeWithoutResult(status -> {
            long t0 = System.nanoTime();
            EligibilityProfile profile = profileRepository.findByUserId(userId).orElseThrow();
            Hibernate.initialize(profile.getSpecialTargets());
            run.profileNanos = System.nanoTime() - t0;
            RegionIndex index = regionDirectory.index();
            long base = statistics.getPrepareStatementCount();
            long lastId = Long.MAX_VALUE;
            while (true) {
                t0 = System.nanoTime();
                List<Opportunity> chunk = em.createQuery(ENTITY_CHUNK_JPQL, Opportunity.class)
                        .setParameter("lastId", lastId).setMaxResults(CHUNK).getResultList();
                if (chunk.isEmpty()) {
                    run.queryNanos += System.nanoTime() - t0;
                    break;
                }
                List<Long> versionIds = chunk.stream().map(o -> o.getCurrentVersion().getId()).toList();
                List<OpportunityCondition> conditions = conditionRepository.findByVersionIdIn(versionIds);
                List<OpportunityRegion> regions = opportunityRegionRepository.findByVersionIdIn(versionIds);
                List<ApplicationWindow> windows = windowRepository.findByVersionIdIn(versionIds);
                long t1 = System.nanoTime();
                run.queryNanos += t1 - t0;

                Map<Long, List<EligibilityConditionInput>> cm = conditions.stream()
                        .collect(Collectors.groupingBy(c -> c.getVersion().getId(),
                                Collectors.mapping(EligibilityConditionInput::from, Collectors.toList())));
                Map<Long, List<String>> rm = regions.stream()
                        .filter(r -> r.getRegionRole() == RegionRole.RESIDENCE)
                        .collect(Collectors.groupingBy(r -> r.getVersion().getId(),
                                Collectors.mapping(r -> r.getRegion().getRegionCode(), Collectors.toList())));
                Map<Long, List<ApplicationPeriod>> wm = windows.stream()
                        .collect(Collectors.groupingBy(w -> w.getVersion().getId(),
                                Collectors.mapping(w -> new ApplicationPeriod(w.getPeriodType(), w.getStartDate(),
                                        w.getEndDate(), w.getValidationStatus(), w.getPeriodText()), Collectors.toList())));
                long t2 = System.nanoTime();
                run.mappingNanos += t2 - t1;

                long qe = statistics.getPrepareStatementCount();
                for (Opportunity o : chunk) {
                    Long versionId = o.getCurrentVersion().getId();
                    judge(run, o.getId(), profile, cm.getOrDefault(versionId, List.of()),
                            rm.getOrDefault(versionId, List.of()), wm.getOrDefault(versionId, List.of()), index);
                }
                run.engineNanos += System.nanoTime() - t2;
                run.engineQueries += statistics.getPrepareStatementCount() - qe;
                lastId = chunk.get(chunk.size() - 1).getId();
                em.clear();
            }
            run.queries = statistics.getPrepareStatementCount() - base;
        });
        return run;
    }

    /** Projection 경로: 판정에 쓰는 컬럼만 JPQL 스칼라로 읽고 저장되지 않는 임시 객체로 엔진에 넘긴다 */
    private Run runProjection(long userId, boolean collect) {
        Run run = new Run();
        if (collect) {
            run.judged = new HashMap<>();
        }
        readOnlyTx.executeWithoutResult(status -> {
            long t0 = System.nanoTime();
            EligibilityProfile profile = profileRepository.findByUserId(userId).orElseThrow();
            Hibernate.initialize(profile.getSpecialTargets());
            run.profileNanos = System.nanoTime() - t0;
            RegionIndex index = regionDirectory.index();
            long base = statistics.getPrepareStatementCount();
            long lastId = Long.MAX_VALUE;
            while (true) {
                t0 = System.nanoTime();
                List<Object[]> chunk = em.createQuery(PROJ_CHUNK_JPQL, Object[].class)
                        .setParameter("lastId", lastId).setMaxResults(CHUNK).getResultList();
                if (chunk.isEmpty()) {
                    run.queryNanos += System.nanoTime() - t0;
                    break;
                }
                List<Long> versionIds = chunk.stream().map(r -> (Long) r[1]).toList();
                Loaded loaded = loadProjection(versionIds);
                long t1 = System.nanoTime();
                run.queryNanos += t1 - t0;

                Mapped mapped = mapProjection(loaded);
                long t2 = System.nanoTime();
                run.mappingNanos += t2 - t1;

                for (Object[] r : chunk) {
                    Long versionId = (Long) r[1];
                    judge(run, (Long) r[0], profile, mapped.conditions().getOrDefault(versionId, List.of()),
                            mapped.residence().getOrDefault(versionId, List.of()),
                            mapped.periods().getOrDefault(versionId, List.of()), index);
                }
                run.engineNanos += System.nanoTime() - t2;
                lastId = (Long) chunk.get(chunk.size() - 1)[0];
                em.clear();
            }
            run.queries = statistics.getPrepareStatementCount() - base;
        });
        return run;
    }

    private void judge(Run run, Long opportunityId, EligibilityProfile profile, List<EligibilityConditionInput> conditions,
                       List<String> residence, List<ApplicationPeriod> periods, RegionIndex index) {
        EligibilityEvaluation evaluation = engine.evaluate(EligibilityProfileInput.from(profile), conditions, residence, index, TODAY);
        ApplicationStatus app = applicationStatusCalculator.calculate(periods, TODAY);
        run.distribution.merge(evaluation.status(), 1L, Long::sum);
        run.evaluated++;
        if (run.judged != null) {
            String conditionResults = evaluation.conditions().stream()
                    .map(c -> c.type() + "|" + c.field() + "|" + c.requirement() + "|" + c.result())
                    .sorted().collect(Collectors.joining(","));
            run.judged.put(opportunityId, evaluation.status() + "|" + app + "|" + conditionResults);
        }
    }

    /**
     * 순수 JDBC로 Hibernate가 실제 만든 SQL을 같은 파라미터로 실행하고 모든 컬럼을 getString으로 읽는다.
     * 엔티티 생성·JSON 파싱·영속성 컨텍스트가 빠진, SQL 실행 + 결과 전송 + JDBC 디코딩 비용.
     */
    private Run runJdbc(SqlSet sqls, boolean projection) {
        Run run = new Run();
        List<ChunkKey> keys = currentChunks;
        readOnlyTx.executeWithoutResult(status -> {
            Connection con = DataSourceUtils.getConnection(dataSource);
            try {
                // 청크 경계·버전 ID는 미리 계산한 값을 쓴다 (Hibernate SQL의 컬럼 구성과 무관하게 같은 행을 읽기 위해)
                for (int i = 0; i <= keys.size(); i++) {
                    long t0 = System.nanoTime();
                    long lastId = i < keys.size() ? keys.get(i).lastId()
                            : keys.isEmpty() ? Long.MAX_VALUE : lastOf(keys.get(keys.size() - 1));
                    try (PreparedStatement ps = con.prepareStatement(sqls.chunk())) {
                        bindChunk(ps, sqls.chunk(), lastId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                readAll(rs);
                            }
                        }
                    }
                    run.queries++;
                    if (i == keys.size()) {
                        run.queryNanos += System.nanoTime() - t0;
                        break;
                    }
                    List<Long> versionIds = keys.get(i).versionIds();
                    for (String sql : List.of(sqls.conditions(), sqls.regions(), sqls.windows())) {
                        try (PreparedStatement ps = con.prepareStatement(sql)) {
                            bindIds(ps, sql, versionIds, projection && sql == sqls.regions());
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    readAll(rs);
                                }
                            }
                        }
                        run.queries++;
                    }
                    run.queryNanos += System.nanoTime() - t0;
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(con, dataSource);
            }
        });
        return run;
    }

    private List<ChunkKey> currentChunks = List.of();

    /** 마지막 청크 다음 조회의 기준 ID (마지막 청크의 가장 작은 기회 ID) */
    private long lastOf(ChunkKey key) {
        return chunkLastIds.getOrDefault(key, Long.MAX_VALUE);
    }

    private final Map<ChunkKey, Long> chunkLastIds = new HashMap<>();

    private static void readAll(ResultSet rs) throws SQLException {
        int n = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= n; i++) {
            rs.getString(i);
        }
    }

    // ───────────────────────── SQL 캡처·바인딩·EXPLAIN ─────────────────────────

    private final Map<FullPath, SqlSet> sqlSets = new EnumMap<>(FullPath.class);

    /** 해당 경로가 첫 청크에서 실제로 준비한 SQL 4개 (청크, 조건, 지역, 신청기간) */
    private SqlSet sqlSet(FullPath path) {
        return sqlSets.computeIfAbsent(path, p -> {
            SqlCapture.start();
            if (p == FullPath.ENTITY) {
                runEntity(ProfileKind.FULL.userId(), false);
            } else {
                runProjection(ProfileKind.FULL.userId(), false);
            }
            List<String> sqls = SqlCapture.stop();
            String chunk = first(sqls, s -> s.contains("from opportunities "));
            String conditions = first(sqls, s -> s.contains("from opportunity_conditions "));
            String regions = first(sqls, s -> s.contains("from opportunity_regions "));
            String windows = first(sqls, s -> s.contains("from application_windows "));
            return new SqlSet(chunk, conditions, regions, windows);
        });
    }

    private static String first(List<String> sqls, java.util.function.Predicate<String> p) {
        return sqls.stream()
                .filter(s -> p.test(s.toLowerCase(Locale.ROOT)))
                .findFirst().orElseThrow(() -> new IllegalStateException("SQL을 찾지 못함: " + sqls));
    }

    private static int placeholders(String sql) {
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') {
                n++;
            }
        }
        return n;
    }

    /** 청크 SQL: where의 lastId, 그 뒤 페이징(fetch first/limit) 순서 */
    private static void bindChunk(PreparedStatement ps, String sql, long lastId) throws SQLException {
        int n = placeholders(sql);
        ps.setLong(1, lastId);
        if (n == 2) {
            ps.setInt(2, CHUNK);
        } else if (n != 1) {
            throw new IllegalStateException("청크 SQL 파라미터 형식 불일치(" + n + "): " + sql);
        }
    }

    /** IN 목록 SQL: 배열 1개, 개별 n개, 패딩 모두 지원. role 파라미터는 마지막 */
    private static void bindIds(PreparedStatement ps, String sql, List<Long> ids, boolean roleParam) throws SQLException {
        int n = placeholders(sql) - (roleParam ? 1 : 0);
        int idx = 1;
        if (n == 1) {
            ps.setArray(idx++, ps.getConnection().createArrayOf("bigint", ids.toArray(Long[]::new)));
        } else if (n >= ids.size()) {
            for (int i = 0; i < n; i++) {
                ps.setLong(idx++, ids.get(Math.min(i, ids.size() - 1)));
            }
        } else {
            throw new IllegalStateException("IN 목록 SQL 파라미터 형식 불일치(" + n + " < " + ids.size() + "): " + sql);
        }
        if (roleParam) {
            ps.setString(idx, RegionRole.RESIDENCE.name());
        }
    }

    private List<ChunkKey> chunkKeys() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, current_version_id FROM opportunities WHERE current_version_id IS NOT NULL ORDER BY id DESC");
        List<ChunkKey> keys = new ArrayList<>();
        long lastId = Long.MAX_VALUE;
        for (int i = 0; i < rows.size(); i += CHUNK) {
            List<Map<String, Object>> part = rows.subList(i, Math.min(i + CHUNK, rows.size()));
            ChunkKey key = new ChunkKey(lastId, part.stream().map(r -> ((Number) r.get("current_version_id")).longValue()).toList());
            keys.add(key);
            lastId = ((Number) part.get(part.size() - 1).get("id")).longValue();
            chunkLastIds.put(key, lastId);
        }
        return keys;
    }

    /** 청크 전체의 EXPLAIN 합계 [planning, execution], 반복 중앙값 */
    private double[] explainFull(SqlSet sqls, List<ChunkKey> chunks, boolean projection, int repeat) {
        List<Double> planning = new ArrayList<>();
        List<Double> execution = new ArrayList<>();
        for (int i = 0; i < repeat; i++) {
            double p = 0;
            double e = 0;
            for (ChunkKey key : chunks) {
                for (String sql : List.of(sqls.chunk(), sqls.conditions(), sqls.regions(), sqls.windows())) {
                    Explain x = explainOnce(sql, key, projection && sql == sqls.regions(), sql == sqls.chunk());
                    p += x.planning();
                    e += x.execution();
                }
            }
            planning.add(p);
            execution.add(e);
        }
        return new double[]{Stat.of(planning).p50(), Stat.of(execution).p50()};
    }

    private static final Pattern PLANNING = Pattern.compile("Planning Time: ([0-9.]+) ms");
    private static final Pattern EXECUTION = Pattern.compile("Execution Time: ([0-9.]+) ms");

    private Explain explainOnce(String sql, ChunkKey key, boolean roleParam, boolean chunkSql) {
        List<String> lines = explainLines("EXPLAIN (ANALYZE, BUFFERS, TIMING OFF) ", sql, key, roleParam, chunkSql);
        double planning = 0;
        double execution = 0;
        String buffers = "";
        for (String line : lines) {
            Matcher m = PLANNING.matcher(line);
            if (m.find()) {
                planning = Double.parseDouble(m.group(1));
            }
            m = EXECUTION.matcher(line);
            if (m.find()) {
                execution = Double.parseDouble(m.group(1));
            }
            if (buffers.isEmpty() && line.trim().startsWith("Buffers:")) {
                buffers = line.trim();
            }
        }
        return new Explain(planning, execution, buffers);
    }

    private String plan(String title, String sql, ChunkKey key, boolean roleParam, boolean chunkSql) {
        List<String> lines = explainLines("EXPLAIN (ANALYZE, BUFFERS) ", sql, key, roleParam, chunkSql);
        return "### " + title + "\n\n```\n" + lines.stream().map(EligibilityBottleneckBenchmarkTest::clip)
                .collect(Collectors.joining("\n")) + "\n```\n";
    }

    private List<String> explainLines(String explainPrefix, String rawSql, ChunkKey key, boolean roleParam, boolean chunkSql) {
        String sql = rawSql;
        String explainSql = explainPrefix + sql;
        return readOnlyTx.execute(s -> {
            Connection con = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = con.prepareStatement(explainSql)) {
                if (chunkSql) {
                    bindChunk(ps, sql, key.lastId());
                } else {
                    bindIds(ps, sql, key.versionIds(), roleParam);
                }
                List<String> lines = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
                return lines;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(con, dataSource);
            }
        });
    }

    /** 목록 서비스가 실제로 만든 페이지·count SQL의 실행 계획 (offset 영향 확인) */
    private void explainListPages(int size, List<String> plans) {
        for (int pageSize : PAGE_SIZES) {
            int lastPage = (size + pageSize - 1) / pageSize - 1;
            for (int page : new int[]{0, lastPage}) {
                SqlCapture.start();
                queryService.evaluateAll(ProfileKind.FULL.userId(), page, pageSize, NOW);
                List<String> sqls = SqlCapture.stop();
                String pageSql = sqls.stream().filter(x -> x.toLowerCase(Locale.ROOT).contains("from opportunities ")
                        && !x.toLowerCase(Locale.ROOT).contains("count(")).findFirst().orElse(null);
                if (pageSql == null) {
                    plans.add("### 목록 페이지 SQL size=" + pageSize + " page=" + page + "\n\n측정 불가: 페이지 SQL을 찾지 못함\n");
                    continue;
                }
                String lower = pageSql.toLowerCase(Locale.ROOT);
                List<Integer> values = new ArrayList<>();
                int offsetAt = lower.indexOf("offset ?");
                int limitAt = Math.max(lower.indexOf("fetch first ?"), lower.indexOf("limit ?"));
                if (offsetAt >= 0 && limitAt >= 0) {
                    values.add(offsetAt < limitAt ? page * pageSize : pageSize);
                    values.add(offsetAt < limitAt ? pageSize : page * pageSize);
                } else if (limitAt >= 0) {
                    values.add(pageSize);
                }
                if (placeholders(pageSql) != values.size()) {
                    plans.add("### 목록 페이지 SQL size=" + pageSize + " page=" + page
                            + "\n\n측정 불가: 파라미터 형식을 판별하지 못함\n\n```\n" + pageSql + "\n```\n");
                    continue;
                }
                List<String> lines = readOnlyTx.execute(s -> {
                    Connection con = DataSourceUtils.getConnection(dataSource);
                    try (PreparedStatement ps = con.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + pageSql)) {
                        for (int i = 0; i < values.size(); i++) {
                            ps.setInt(i + 1, values.get(i));
                        }
                        List<String> out = new ArrayList<>();
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                out.add(rs.getString(1));
                            }
                        }
                        return out;
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        DataSourceUtils.releaseConnection(con, dataSource);
                    }
                });
                plans.add("### 목록 페이지 SQL size=" + pageSize + " page=" + page + " (서비스 실제 SQL)\n\n```\n"
                        + clip(pageSql) + "\n\n" + lines.stream().map(EligibilityBottleneckBenchmarkTest::clip)
                        .collect(Collectors.joining("\n")) + "\n```\n");
            }
        }
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + " …(생략)" : s;
    }

    // ───────────────────────── D. 후보 축소 ─────────────────────────

    private List<CandidateRow> checkCandidates(int size, Complexity complexity, List<String> plans) {
        List<CandidateRow> rows = new ArrayList<>();
        RegionIndex index = regionDirectory.index();
        for (ProfileKind kind : ProfileKind.values()) {
            Run full = runEntity(kind.userId(), true);
            Object[] params = readOnlyTx.execute(s -> {
                EligibilityProfile p = profileRepository.findByUserId(kind.userId()).orElseThrow();
                Integer age = p.getBirthDate() == null ? null : Period.between(p.getBirthDate(), TODAY).getYears();
                String code = p.getResidenceRegion() == null ? null : p.getResidenceRegion().getRegionCode();
                List<String> chain = code == null ? List.of() : index.contains(code) ? index.ancestorsAndSelf(code) : List.of(code);
                return new Object[]{age, chain.toArray(String[]::new), code != null};
            });
            Set<Long> candidates = new HashSet<>(candidateIds(params));
            List<Double> millis = new ArrayList<>();
            for (int i = 0; i < 23; i++) {
                long t0 = System.nanoTime();
                candidateIds(params);
                if (i >= 3) {
                    millis.add((System.nanoTime() - t0) / 1e6);
                }
            }
            int unsafe = 0;
            int excluded = 0;
            for (Map.Entry<Long, String> e : full.judged.entrySet()) {
                if (!candidates.contains(e.getKey())) {
                    excluded++;
                    if (!e.getValue().startsWith(EligibilityStatus.NOT_ELIGIBLE.name() + "|")) {
                        unsafe++;
                    }
                }
            }
            if (unsafe > 0) {
                failures.add("후보 축소가 판정 대상 정책을 잘못 제외: N=" + size + " " + complexity + " " + kind + " " + unsafe + "건");
            }
            long notEligible = full.distribution.getOrDefault(EligibilityStatus.NOT_ELIGIBLE, 0L);
            rows.add(new CandidateRow(size, complexity, kind, full.evaluated, candidates.size(), excluded, notEligible,
                    unsafe, Stat.of(millis)));
            if (plans != null && kind == ProfileKind.FULL) {
                plans.add("### 후보 축소 SQL (FULL)\n\n```\n" + String.join("\n", explainCandidate(params)) + "\n```\n");
            }
        }
        return rows;
    }

    private List<Long> candidateIds(Object[] params) {
        return jdbc.query(CANDIDATE_SQL, ps -> bindCandidate(ps, params), (rs, i) -> rs.getLong(1));
    }

    private List<String> explainCandidate(Object[] params) {
        return jdbc.query("EXPLAIN (ANALYZE, BUFFERS) " + CANDIDATE_SQL, ps -> bindCandidate(ps, params),
                (rs, i) -> clip(rs.getString(1)));
    }

    private static void bindCandidate(PreparedStatement ps, Object[] params) throws SQLException {
        if (params[0] == null) {
            ps.setNull(1, Types.INTEGER);
        } else {
            ps.setInt(1, (Integer) params[0]);
        }
        ps.setArray(2, ps.getConnection().createArrayOf("text", (String[]) params[1]));
        ps.setBoolean(3, (Boolean) params[2]);
    }

    // ───────────────────────── 보고서 ─────────────────────────

    private String report(List<AuditRow> audits, List<ListRow> lists, List<FullRow> fulls, List<CandidateRow> candidates,
                          Map<String, List<String>> sqlSamples, List<String> plans,
                          int warmup, int iterations, int fullWarmup, int fullIterations, int explainRepeat) throws SQLException {
        StringBuilder sb = new StringBuilder("# 판정 병목 분석 측정 결과\n\n## 실행 환경\n\n| 항목 | 값 |\n|---|---|\n");
        Runtime rt = Runtime.getRuntime();
        row(sb, "측정 시각", LocalDateTime.now(KST).toString());
        row(sb, "JVM", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        row(sb, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        row(sb, "CPU 코어(JVM 인식)", String.valueOf(rt.availableProcessors()));
        row(sb, "최대 힙", (rt.maxMemory() / 1024 / 1024) + " MB");
        row(sb, "PostgreSQL", jdbc.queryForObject("SELECT version()", String.class));
        row(sb, "shared_buffers", jdbc.queryForObject("SHOW shared_buffers", String.class));
        try (Connection con = dataSource.getConnection()) {
            row(sb, "JDBC 드라이버", con.getMetaData().getDriverName() + " " + con.getMetaData().getDriverVersion());
        }
        row(sb, "Spring Boot", SpringBootVersion.getVersion());
        row(sb, "Hibernate", org.hibernate.Version.getVersionString());
        row(sb, "SQL 수집", "StatementInspector 등록 (수집하지 않을 때는 SQL을 그대로 반환만 함)");
        row(sb, "JVM 전체 워밍업", "1,000건 MIXED로 모든 경로 30회 (기록 안 함)");
        row(sb, "목록 반복", "워밍업 " + warmup + "회 + 측정 " + iterations + "회, 서비스·Projection 교차 실행");
        row(sb, "전체 판정 반복", "워밍업 " + fullWarmup + "회 + 측정 " + fullIterations + "회, 4개 경로 순서를 회차마다 회전");
        row(sb, "EXPLAIN 반복", explainRepeat + "회 중앙값, ANALYZE·BUFFERS·TIMING OFF, 청크 합계");
        row(sb, "기준 시각", NOW.toString());
        sb.append("\n단위: ms. 쿼리 수는 Hibernate가 준비한 SQL 문 수(JDBC 경로는 직접 실행한 문 수).\n");

        sb.append("\n## A. 쿼리 감사 (evaluateAll size=20, 프로필별 SQL 문 수)\n\n| 정책 수 | 복잡도 | 페이지 |");
        for (ProfileKind k : ProfileKind.values()) {
            sb.append(" ").append(k).append(" |");
        }
        sb.append("\n|---|---|---|").append("---|".repeat(ProfileKind.values().length)).append("\n");
        Map<String, List<AuditRow>> grouped = audits.stream().collect(Collectors.groupingBy(
                a -> a.size() + "|" + a.complexity() + "|" + a.page(), LinkedHashMap::new, Collectors.toList()));
        for (List<AuditRow> g : grouped.values()) {
            AuditRow a0 = g.get(0);
            sb.append("| ").append(a0.size()).append(" | ").append(a0.complexity()).append(" | ").append(a0.page()).append(" |");
            for (ProfileKind k : ProfileKind.values()) {
                g.stream().filter(a -> a.profile() == k).findFirst().ifPresent(a -> sb.append(" ").append(a.queries()).append(" |"));
            }
            sb.append("\n");
        }
        sb.append("\n프로필: ");
        for (ProfileKind k : ProfileKind.values()) {
            sb.append(k).append("=").append(k.description).append("; ");
        }
        sb.append("\n\n### 실제 SQL (마지막 정책 수, MIXED)\n\n");
        for (Map.Entry<String, List<String>> e : sqlSamples.entrySet()) {
            sb.append("**").append(e.getKey()).append("** (").append(e.getValue().size()).append("개)\n\n```\n");
            List<String> distinct = new ArrayList<>(new LinkedHashSet<>(e.getValue()));
            for (int i = 0; i < distinct.size(); i++) {
                long times = Collections.frequency(e.getValue(), distinct.get(i));
                sb.append(i + 1).append(". [").append(times).append("회] ").append(clip(distinct.get(i))).append("\n");
            }
            sb.append("```\n\n");
        }

        sb.append("## B-1. 목록 판정: 서비스 vs 테스트 Projection 경로\n\n");
        sb.append("| 정책 수 | 복잡도 | size | 페이지 | 서비스 p50 | 서비스 p95 | Projection p50 | Projection p95 | p50 차이 | 쿼리(서비스/Projection) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (ListRow l : lists) {
            sb.append(String.format("| %d | %s | %d | %s | %.2f | %.2f | %.2f | %.2f | %s | %d / %d |%n",
                    l.size(), l.complexity(), l.pageSize(), l.page(), l.service().p50(), l.service().p95(),
                    l.projection().p50(), l.projection().p95(), pct(l.service().p50(), l.projection().p50()),
                    l.serviceQueries(), l.projectionQueries()));
        }

        sb.append("\n## B-2. 전체 판정: 엔티티 vs Projection (FULL 프로필, 프로필 조회 제외)\n\n");
        sb.append("| 정책 수 | 복잡도 | 경로 | 조회 p50 | 조회 p95 | 매핑 p50 | 엔진 p50 | 엔진 p95 | 합계 p50 | 합계 p95 | 쿼리 수 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (FullRow f : fulls) {
            for (FullPath p : List.of(FullPath.ENTITY, FullPath.PROJECTION)) {
                Stat[] s = f.stats().get(p);
                sb.append(String.format("| %d | %s | %s | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %d |%n",
                        f.size(), f.complexity(), p, s[0].p50(), s[0].p95(), s[1].p50(), s[2].p50(), s[2].p95(),
                        s[3].p50(), s[3].p95(), f.queries().get(p)));
            }
            sb.append(String.format("| %d | %s | 차이 | %s | | | | | %s | | |%n", f.size(), f.complexity(),
                    pct(f.stats().get(FullPath.ENTITY)[0].p50(), f.stats().get(FullPath.PROJECTION)[0].p50()),
                    pct(f.stats().get(FullPath.ENTITY)[3].p50(), f.stats().get(FullPath.PROJECTION)[3].p50())));
        }

        sb.append("\n## C. 조회 구간 분리 (전체 판정, 청크 합계 p50)\n\n");
        sb.append("같은 SQL을 세 방식으로 실행한 값입니다. 차이 열은 측정값끼리의 뺄셈입니다.\n\n");
        sb.append("| 정책 수 | 복잡도 | SQL | ① 서버 계획 | ② 서버 실행 | ③ JDBC 실행+전송+읽기 | ④ Hibernate 조회 | ③−② | ④−③ | 쿼리 수 | 첫 청크 조건 조회 Buffers |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (FullRow f : fulls) {
            double jdbcE = f.stats().get(FullPath.JDBC_ENTITY_SQL)[0].p50();
            double hibE = f.stats().get(FullPath.ENTITY)[0].p50();
            double jdbcP = f.stats().get(FullPath.JDBC_PROJECTION_SQL)[0].p50();
            double hibP = f.stats().get(FullPath.PROJECTION)[0].p50();
            sb.append(String.format("| %d | %s | 엔티티 | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %d | %s |%n",
                    f.size(), f.complexity(), f.planningEntity(), f.explainEntity(), jdbcE, hibE,
                    jdbcE - f.explainEntity(), hibE - jdbcE, f.queries().get(FullPath.JDBC_ENTITY_SQL), f.buffersEntity()));
            sb.append(String.format("| %d | %s | Projection | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %d | %s |%n",
                    f.size(), f.complexity(), f.planningProjection(), f.explainProjection(), jdbcP, hibP,
                    jdbcP - f.explainProjection(), hibP - jdbcP, f.queries().get(FullPath.JDBC_PROJECTION_SQL), f.buffersProjection()));
        }
        sb.append("\n엔진 구간 쿼리 수(ENTITY, 최대): ");
        sb.append(fulls.stream().map(f -> f.size() + "/" + f.complexity() + "=" + f.entityEngineQueries()).collect(Collectors.joining(", ")));

        sb.append("\n\n## D. 후보 축소 SQL 검증\n\n");
        sb.append("| 정책 수 | 복잡도 | 프로필 | 전체 | 후보 | 제외 | 상세 판정 NOT_ELIGIBLE | 잘못 제외 | 후보 SQL p50 | 후보 SQL p95 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (CandidateRow c : candidates) {
            sb.append(String.format("| %d | %s | %s | %d | %d | %d | %d | %d | %.2f | %.2f |%n", c.size(), c.complexity(),
                    c.profile(), c.total(), c.candidates(), c.excluded(), c.notEligible(), c.unsafe(), c.sql().p50(), c.sql().p95()));
        }
        sb.append("\n```\n").append(CANDIDATE_SQL).append("```\n");

        sb.append("\n## 부록: 실행 계획 (마지막 정책 수, MIXED)\n\n");
        plans.forEach(p -> sb.append(p).append("\n"));

        sb.append("\n## 검증 실패\n\n");
        sb.append(failures.isEmpty() ? "없음\n" : String.join("\n", failures) + "\n");
        return sb.toString();
    }

    private static String pct(double before, double after) {
        return before == 0 ? "-" : String.format("%+.1f%%", (after - before) / before * 100);
    }

    private static void row(StringBuilder sb, String key, String value) {
        sb.append("| ").append(key).append(" | ").append(value == null ? "" : value.replace("|", "/")).append(" |\n");
    }

    private static int intEnv(String name, int defaultValue) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? defaultValue : Integer.parseInt(v.trim());
    }

    private static List<Integer> intList(String name, String defaultValue) {
        String v = System.getenv(name);
        return Arrays.stream((v == null || v.isBlank() ? defaultValue : v).split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).map(Integer::parseInt).toList();
    }
}
