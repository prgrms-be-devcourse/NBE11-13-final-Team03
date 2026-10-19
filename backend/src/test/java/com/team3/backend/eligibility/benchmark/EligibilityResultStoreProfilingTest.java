package com.team3.backend.eligibility.benchmark;

import com.team3.backend.eligibility.engine.EligibilityProfileInput;
import com.team3.backend.eligibility.benchmark.BenchmarkData.Complexity;
import com.team3.backend.eligibility.benchmark.BenchmarkData.ProfileKind;
import com.team3.backend.eligibility.benchmark.BenchmarkData.Template;
import com.team3.backend.eligibility.benchmark.StoreSqlCounter.Snapshot;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultUpsertRepository.UpsertRow;
import com.team3.backend.eligibility.dto.EligibilityResultFreshness;
import com.team3.backend.eligibility.engine.ConditionEvaluation;
import com.team3.backend.eligibility.engine.EligibilityConditionInput;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.dto.ApplicationWindowRow;
import com.team3.backend.opportunity.dto.ConditionRow;
import com.team3.backend.opportunity.dto.OpportunityVersionRef;
import com.team3.backend.opportunity.dto.RegionCodeRow;
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
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판정 결과 저장(EligibilityResultService.refreshAll)의 구간별 계측. 운영 코드는 바꾸지 않는다.
 *
 * 실행 (EligibilityBenchmarkTest와 같은 전용 DB):
 *   YON_BENCH_DB_URL=jdbc:postgresql://localhost:5432/yon_bench sh ./gradlew --no-daemon cleanTest test --tests '*EligibilityResultStoreProfilingTest'
 * 선택 환경변수: YON_BENCH_SIZES(1000,5000,10000), YON_BENCH_RESULT_COLD_ITERATIONS(5), YON_BENCH_RESULT_WARM_ITERATIONS(10)
 * 결과: build/reports/eligibility-benchmark/store-profile-*.md
 *
 * 방법
 * 1. 재현 경로: refreshAll / refreshTargets / EligibilityEvaluator.judge의 호출을 같은 순서·같은 레포지토리 메서드로
 *    테스트에서 그대로 실행하고, 호출 사이에 시각·SQL 수를 찍어 구간을 나눈다. 비공개 변환 메서드(toMap, nextBirthday)는
 *    리플렉션으로 운영 코드를 그대로 호출한다. 트랜잭션은 운영(@Transactional, 메서드 반환 후 commit)과 같은 범위로 직접 열고
 *    commit 시간을 따로 잰다.
 * 2. 동등성 검증: 데이터셋마다 운영 refreshAll과 재현 경로를 각각 한 번 실행해 SQL 종류별 개수와 저장된 행 내용이 같은지
 *    확인한다. 다르면 실패한다 (재현 경로의 구간 수치를 믿을 수 있게 하기 위해).
 * 3. 변경 감지 실험 (이전 저장 방식의 COMPLEX 추가 UPDATE 원인 확인용): 조건 하나만 담은 결과 엔티티를
 *    persist → flush → flush 해서, 어떤 조건 값이 불필요한 UPDATE를 만드는지 본다. 현재 저장 경로는 엔티티를 쓰지 않는다.
 *
 * 구간
 * - PROFILE·PAGE: 프로필 조회, 정책 현재 버전 페이지 조회 (1,000건)
 * - A 최신성: findFreshness + 오래된 것 고르기
 * - B 정책 데이터: 조건·거주지 지역·신청기간 Projection 조회 (judge 내부와 같은 쿼리)
 * - C 엔진: EligibilityEngine.evaluate + ApplicationStatusCalculator (특화대상 지연 로딩 SQL이 있으면 여기에 잡힌다)
 * - D 구성: 조건 결과 Map 변환 + 저장 행(UpsertRow) 생성
 * - E UPSERT: 저장 묶음(yon.eligibility.result-write-batch-size)마다 JSON 변환 + INSERT … ON CONFLICT SQL 1개
 *   (StoreSqlCounter는 SQL 첫 단어로 세므로 UPSERT는 INSERT로 잡힌다)
 * - F1 flush: 남은 변경 실행 (UPSERT는 영속성 컨텍스트를 거치지 않으므로 보통 0건)
 * - F2 clear: em.clear + 프로필 재조회
 * - F3 commit
 * SQL 수는 Hibernate가 준비한 SQL(StatementInspector) 기준, 데이터 준비용 JdbcTemplate SQL은 제외.
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
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.team3.backend.eligibility.benchmark.StoreSqlCounter",
        "logging.level.org.hibernate.SQL=OFF"
})
@Import({EligibilityResultService.class, EligibilityEvaluator.class, EligibilityEngine.class, ApplicationStatusCalculator.class,
        YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class, IncomeNormalizer.class,
        YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class EligibilityResultStoreProfilingTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
    private static final LocalDate TODAY = NOW.atZoneSameInstant(KST).toLocalDate();
    /** EligibilityBenchmarkTest와 같은 프로필 (BenchmarkData FULL = user 1) */
    private static final long USER_ID = ProfileKind.FULL.userId();
    private static final long EXPERIMENT_USER_ID = 999_999L;

    enum Seg {
        PROFILE("프로필 조회"),
        PAGE("정책 페이지 조회"),
        FRESHNESS("A 최신성 확인"),
        POLICY("B 정책 데이터 조회"),
        ENGINE("C 판정 엔진"),
        BUILD("D 저장 행 구성"),
        SAVE("E UPSERT"),
        FLUSH("F1 flush"),
        CLEAR("F2 clear·프로필 재조회"),
        COMMIT("F3 commit");

        final String label;

        Seg(String label) {
            this.label = label;
        }
    }

    @Autowired private EligibilityResultService resultService;
    @Autowired private EligibilityEngine engine;
    @Autowired private ApplicationStatusCalculator applicationStatusCalculator;
    @Autowired private YouthPolicyNormalizationService normalizationService;
    @Autowired private RegionDirectory regionDirectory;
    @Autowired private RegionRepository regionRepository;
    @Autowired private EligibilityProfileRepository profileRepository;
    @Autowired private EligibilityResultRepository resultRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private OpportunityConditionRepository conditionRepository;
    @Autowired private OpportunityRegionRepository opportunityRegionRepository;
    @Autowired private ApplicationWindowRepository windowRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private JdbcTemplate jdbc;
    private Statistics statistics;
    private Method toMap;
    private Method nextBirthday;
    private int chunk;
    private int writeBatchSize;

    /** 한 번의 refreshAll(재현) 구간별 누적값 */
    private final class Run {
        final long[] nanos = new long[Seg.values().length];
        final Snapshot[] sql = new Snapshot[Seg.values().length];
        long prepared;
        int judged;

        Run() {
            Arrays.fill(sql, Snapshot.ZERO);
        }

        long totalNanos() {
            return Arrays.stream(nanos).sum();
        }

        Snapshot totalSql() {
            Snapshot s = Snapshot.ZERO;
            for (Snapshot x : sql) {
                s = s.plus(x);
            }
            return s;
        }
    }

    private <T> T timed(Run run, Seg seg, Supplier<T> body) {
        Snapshot s0 = StoreSqlCounter.now();
        long p0 = statistics.getPrepareStatementCount();
        long t0 = System.nanoTime();
        T value = body.get();
        run.nanos[seg.ordinal()] += System.nanoTime() - t0;
        run.sql[seg.ordinal()] = run.sql[seg.ordinal()].plus(StoreSqlCounter.now().minus(s0));
        run.prepared += statistics.getPrepareStatementCount() - p0;
        return value;
    }

    private void timedVoid(Run run, Seg seg, Runnable body) {
        timed(run, seg, () -> {
            body.run();
            return null;
        });
    }

    private record Stat(double p50, double p95) {
        static Stat of(List<Double> values) {
            List<Double> s = new ArrayList<>(values);
            Collections.sort(s);
            return new Stat(rank(s, 0.50), rank(s, 0.95));
        }

        private static double rank(List<Double> sorted, double p) {
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }

    private record Equivalence(double operatingMillis, double replicaMillis, Snapshot operatingSql, Snapshot replicaSql,
                               long operatingPrepared, long replicaPrepared, int rows, boolean rowsEqual) {
    }

    private record DatasetResult(int size, Complexity complexity, Equivalence equivalence,
                                 List<Run> cold, List<Run> warm) {
    }

    private record ExperimentRow(String template, String type, String field, String result, String valueTypes,
                                 long updatesFirstFlush, long updatesSecondFlush) {
    }

    @Test
    void 판정_결과_저장_구간별_계측() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate writeTx = new TransactionTemplate(transactionManager);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        toMap = EligibilityResultService.class.getDeclaredMethod("toMap", ConditionEvaluation.class);
        toMap.setAccessible(true);
        nextBirthday = EligibilityResultService.class.getDeclaredMethod("nextBirthday", LocalDate.class, LocalDate.class);
        nextBirthday.setAccessible(true);
        Field chunkField = EligibilityResultService.class.getDeclaredField("CHUNK");
        chunkField.setAccessible(true);
        chunk = chunkField.getInt(null);
        Field batchField = EligibilityResultService.class.getDeclaredField("writeBatchSize");
        batchField.setAccessible(true);
        writeBatchSize = batchField.getInt(AopTestUtils.getTargetObject(resultService));

        List<Integer> sizes = intList("YON_BENCH_SIZES", "1000,5000,10000");
        int coldIterations = intEnv("YON_BENCH_RESULT_COLD_ITERATIONS", 5);
        int warmIterations = intEnv("YON_BENCH_RESULT_WARM_ITERATIONS", 10);

        BenchmarkData data = new BenchmarkData(jdbc, writeTx, regionRepository, regionDirectory, profileRepository,
                normalizationService);
        data.seedRegions();
        data.seedProfiles();
        Map<String, Template> templates = data.normalizeTemplates();
        data.verifyTemplateCharacteristics(templates);

        List<ExperimentRow> experiment = dirtyCheckExperiment(templates);

        // 템플릿은 복제 원본으로만 쓰고 판정 대상에서는 뺀다 (EligibilityBenchmarkTest와 같음)
        jdbc.update("UPDATE opportunities SET current_version_id = NULL WHERE id < ?", BenchmarkData.CLONE_BASE);

        List<DatasetResult> results = new ArrayList<>();
        for (int size : sizes) {
            for (Complexity complexity : Complexity.values()) {
                clearResults();
                data.clearClones();
                Map<String, List<Long>> ids = data.seedClones(templates, complexity, size);
                data.verifyClones(templates, ids, size);
                jdbc.execute("VACUUM ANALYZE");

                Equivalence equivalence = verifyEquivalence(size);

                List<Run> cold = new ArrayList<>();
                for (int i = 0; i < coldIterations + 1; i++) {
                    clearResults();
                    Run run = replicaRefreshAll();
                    assertThat(run.judged).isEqualTo(size);
                    if (i > 0) {  // 첫 회는 워밍업
                        cold.add(run);
                    }
                }
                List<Run> warm = new ArrayList<>();
                for (int i = 0; i < warmIterations + 3; i++) {
                    Run run = replicaRefreshAll();
                    assertThat(run.judged).isZero();
                    if (i >= 3) {
                        warm.add(run);
                    }
                }
                results.add(new DatasetResult(size, complexity, equivalence, cold, warm));
                System.out.printf("[store-profile] N=%d %s done%n", size, complexity);
            }
        }
        clearResults();
        data.clearClones();

        String report = report(results, experiment, coldIterations, warmIterations);
        Path dir = Path.of("build", "reports", "eligibility-benchmark");
        Files.createDirectories(dir);
        Path file = dir.resolve("store-profile-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(file, report);
        System.out.println(report);
        System.out.println("[store-profile] report: " + file.toAbsolutePath());
    }

    // ───────────────────────── 동등성 검증 ─────────────────────────

    /** 운영 refreshAll과 재현 경로가 같은 SQL을 같은 수만큼 실행하고 같은 행을 저장하는지 */
    private Equivalence verifyEquivalence(int size) {
        clearResults();
        Snapshot s0 = StoreSqlCounter.now();
        long p0 = statistics.getPrepareStatementCount();
        long t0 = System.nanoTime();
        int judged = resultService.refreshAll(USER_ID, NOW);
        long t1 = System.nanoTime();
        Snapshot operatingSql = StoreSqlCounter.now().minus(s0);
        long operatingPrepared = statistics.getPrepareStatementCount() - p0;
        assertThat(judged).isEqualTo(size);
        List<Map<String, Object>> operatingRows = dumpRows();

        clearResults();
        Run replica = replicaRefreshAll();
        List<Map<String, Object>> replicaRows = dumpRows();

        assertThat(replica.totalSql()).as("운영 refreshAll과 재현 경로의 SQL 종류별 개수").isEqualTo(operatingSql);
        assertThat(replicaRows).as("운영 refreshAll과 재현 경로가 저장한 행").isEqualTo(operatingRows);
        return new Equivalence((t1 - t0) / 1e6, replica.totalNanos() / 1e6, operatingSql, replica.totalSql(),
                operatingPrepared, replica.prepared, replicaRows.size(), true);
    }

    private List<Map<String, Object>> dumpRows() {
        return jdbc.queryForList("SELECT opportunity_id, opportunity_version_id, profile_version, rule_version, "
                + "eligibility_status, reason, condition_results::text AS condition_results, valid_until, evaluated_at "
                + "FROM eligibility_results WHERE user_id = ? ORDER BY opportunity_id", USER_ID);
    }

    private void clearResults() {
        jdbc.update("DELETE FROM eligibility_results");
    }

    // ───────────────────────── 재현 경로 ─────────────────────────

    /** EligibilityResultService.refreshAll(userId, NOW)과 같은 호출 순서. 트랜잭션도 같은 범위 */
    private Run replicaRefreshAll() {
        Run run = new Run();
        TransactionStatus tx = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            EligibilityProfile profile = timed(run, Seg.PROFILE,
                    () -> profileRepository.findByUserId(USER_ID).orElseThrow());
            int page = 0;
            Page<OpportunityVersionRef> refs;
            do {
                final int p = page++;
                refs = timed(run, Seg.PAGE, () -> opportunityRepository.findAllCurrentVersionRefs(
                        PageRequest.of(p, chunk, Sort.by(Sort.Direction.ASC, "id"))));
                run.judged += replicaTargets(run, profile, refs.getContent());
                timedVoid(run, Seg.FLUSH, () -> resultRepository.flush());
                profile = timed(run, Seg.CLEAR, () -> {
                    em.clear();
                    return profileRepository.findByUserId(USER_ID).orElseThrow();
                });
            } while (refs.hasNext());
        } catch (RuntimeException e) {
            transactionManager.rollback(tx);
            throw e;
        }
        timedVoid(run, Seg.COMMIT, () -> transactionManager.commit(tx));
        return run;
    }

    /** EligibilityResultService.refreshTargets + EligibilityEvaluator.judge와 같은 호출 순서 */
    private int replicaTargets(Run run, EligibilityProfile profile, List<OpportunityVersionRef> refs) {
        if (refs.isEmpty()) {
            return 0;
        }
        List<Long> opportunityIds = refs.stream().map(OpportunityVersionRef::opportunityId).toList();
        Map<Long, EligibilityResultFreshness> freshness = timed(run, Seg.FRESHNESS, () ->
                resultRepository.findFreshness(profile.getUserId(), opportunityIds).stream()
                        .collect(Collectors.toMap(EligibilityResultFreshness::opportunityId, Function.identity())));
        List<OpportunityVersionRef> stale = timed(run, Seg.FRESHNESS, () -> refs.stream()
                .filter(r -> {
                    EligibilityResultFreshness f = freshness.get(r.opportunityId());
                    return f == null || !f.isFresh(r.versionId(), profile.getProfileVersion(),
                            EligibilityResultService.RULE_VERSION, TODAY);
                })
                .toList());
        if (stale.isEmpty()) {
            return 0;
        }

        // EligibilityEvaluator.judge: 조회 3개(B) → 엔진(C)
        Set<Long> versionIds = stale.stream().map(OpportunityVersionRef::versionId).collect(Collectors.toSet());
        Map<Long, List<EligibilityConditionInput>> conditions = timed(run, Seg.POLICY, () ->
                conditionRepository.findRowsByVersionIdIn(versionIds).stream()
                        .collect(Collectors.groupingBy(ConditionRow::versionId, Collectors.mapping(
                                c -> new EligibilityConditionInput(c.conditionType(), c.requirementType(), c.conditionField(),
                                        c.expression(), c.validationStatus()),
                                Collectors.toList()))));
        Map<Long, List<String>> residence = timed(run, Seg.POLICY, () ->
                opportunityRegionRepository.findCodesByVersionIdInAndRole(versionIds, RegionRole.RESIDENCE).stream()
                        .collect(Collectors.groupingBy(RegionCodeRow::versionId,
                                Collectors.mapping(RegionCodeRow::regionCode, Collectors.toList()))));
        Map<Long, List<ApplicationPeriod>> periods = timed(run, Seg.POLICY, () ->
                windowRepository.findRowsByVersionIdIn(versionIds).stream()
                        .collect(Collectors.groupingBy(ApplicationWindowRow::versionId, Collectors.mapping(
                                w -> new ApplicationPeriod(w.periodType(), w.startDate(), w.endDate(),
                                        w.validationStatus(), w.periodText()),
                                Collectors.toList()))));
        Map<Long, EligibilityEvaluation> evaluations = timed(run, Seg.ENGINE, () -> {
            RegionIndex index = regionDirectory.index();
            EligibilityProfileInput input = EligibilityProfileInput.from(profile);
            Map<Long, EligibilityEvaluation> out = new HashMap<>();
            for (Long versionId : versionIds) {
                EligibilityEvaluation evaluation = engine.evaluate(input, conditions.getOrDefault(versionId, List.of()),
                        residence.getOrDefault(versionId, List.of()), index, TODAY);
                applicationStatusCalculator.calculate(periods.getOrDefault(versionId, List.of()), TODAY);
                out.put(versionId, evaluation);
            }
            return out;
        });

        LocalDate validUntil = invokeNextBirthday(profile.getBirthDate());
        LocalDateTime evaluatedAt = NOW.atZoneSameInstant(KST).toLocalDateTime();
        List<UpsertRow> rows = timed(run, Seg.BUILD, () -> {
            List<UpsertRow> out = new ArrayList<>(stale.size());
            for (OpportunityVersionRef ref : stale) {
                EligibilityEvaluation evaluation = evaluations.get(ref.versionId());
                List<Map<String, Object>> conditionResults = evaluation.conditions().stream().map(this::invokeToMap).toList();
                out.add(new UpsertRow(ref.opportunityId(), ref.versionId(), evaluation.status(), evaluation.reason(),
                        conditionResults, validUntil));
            }
            return out;
        });
        int batchSize = Math.max(1, writeBatchSize);
        timedVoid(run, Seg.SAVE, () -> {
            for (int from = 0; from < rows.size(); from += batchSize) {
                resultRepository.upsert(profile.getUserId(), profile.getProfileVersion(),
                        EligibilityResultService.RULE_VERSION, evaluatedAt,
                        rows.subList(from, Math.min(from + batchSize, rows.size())));
            }
        });
        return stale.size();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeToMap(ConditionEvaluation c) {
        try {
            return (Map<String, Object>) toMap.invoke(null, c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private LocalDate invokeNextBirthday(LocalDate birthDate) {
        try {
            return (LocalDate) nextBirthday.invoke(null, birthDate, TODAY);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────── 변경 감지 실험 ─────────────────────────

    /**
     * 템플릿 정책마다, FULL 프로필로 판정한 조건 결과를 하나씩 담은 행을 persist → flush → flush 한다.
     * persist 직후 아무것도 바꾸지 않았으므로 flush에서 UPDATE가 나오면 변경 감지가 값이 바뀌었다고 오판한 것이다.
     * 실험 행은 별도 사용자 ID로 넣고 트랜잭션을 롤백한다.
     */
    private List<ExperimentRow> dirtyCheckExperiment(Map<String, Template> templates) {
        List<ExperimentRow> rows = new ArrayList<>();
        TransactionStatus tx = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            long fakeOpportunityId = 9_000_000L;
            for (Template t : templates.values()) {
                EligibilityProfile profile = profileRepository.findByUserId(USER_ID).orElseThrow();  // 템플릿마다 em.clear 하므로 다시 읽는다
                List<EligibilityConditionInput> inputs = conditionRepository.findRowsByVersionIdIn(List.of(t.versionId())).stream()
                        .map(c -> new EligibilityConditionInput(c.conditionType(), c.requirementType(), c.conditionField(),
                                c.expression(), c.validationStatus()))
                        .toList();
                List<String> residence = opportunityRegionRepository
                        .findCodesByVersionIdInAndRole(List.of(t.versionId()), RegionRole.RESIDENCE).stream()
                        .map(RegionCodeRow::regionCode).toList();
                EligibilityEvaluation evaluation = engine.evaluate(EligibilityProfileInput.from(profile), inputs, residence, regionDirectory.index(), TODAY);

                List<List<ConditionEvaluation>> groups = new ArrayList<>();
                evaluation.conditions().forEach(c -> groups.add(List.of(c)));
                groups.add(evaluation.conditions());  // 조건 전체를 담은 실제 행
                for (List<ConditionEvaluation> group : groups) {
                    List<Map<String, Object>> conditionResults = group.stream().map(this::invokeToMap).toList();
                    EligibilityResult result = EligibilityResult.builder()
                            .userId(EXPERIMENT_USER_ID)
                            .opportunityId(fakeOpportunityId++)
                            .opportunityVersionId(t.versionId())
                            .profileVersion(profile.getProfileVersion())
                            .ruleVersion(EligibilityResultService.RULE_VERSION)
                            .eligibilityStatus(evaluation.status())
                            .reason(evaluation.reason())
                            .conditionResults(conditionResults)
                            .validUntil(invokeNextBirthday(profile.getBirthDate()))
                            .evaluatedAt(NOW.atZoneSameInstant(KST).toLocalDateTime())
                            .build();
                    em.persist(result);
                    Snapshot s0 = StoreSqlCounter.now();
                    em.flush();
                    long first = StoreSqlCounter.now().minus(s0).update();
                    Snapshot s1 = StoreSqlCounter.now();
                    em.flush();
                    long second = StoreSqlCounter.now().minus(s1).update();
                    boolean whole = group.size() != 1;
                    ConditionEvaluation c = group.getFirst();
                    rows.add(new ExperimentRow(t.key(),
                            whole ? "(조건 전체 " + group.size() + "개)" : String.valueOf(c.type()),
                            whole ? "-" : c.field(),
                            whole ? String.valueOf(evaluation.status()) : String.valueOf(c.result()),
                            whole ? "-" : "expected=" + describe(c.expected()) + ", actual=" + describe(c.actual()),
                            first, second));
                }
                em.clear();
            }
        } finally {
            transactionManager.rollback(tx);
        }
        return rows;
    }

    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map<?, ?> m) {
            return "Map{" + m.entrySet().stream().limit(4)
                    .map(e -> e.getKey() + ":" + (e.getValue() == null ? "null" : e.getValue().getClass().getSimpleName()))
                    .collect(Collectors.joining(",")) + (m.size() > 4 ? ",…" : "") + "}";
        }
        if (v instanceof Collection<?> c) {
            return "List<" + c.stream().map(x -> x == null ? "null" : x.getClass().getSimpleName()).distinct()
                    .collect(Collectors.joining("|")) + ">";
        }
        return v.getClass().getSimpleName();
    }

    // ───────────────────────── 보고서 ─────────────────────────

    private String report(List<DatasetResult> results, List<ExperimentRow> experiment, int coldIterations, int warmIterations) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 판정 결과 저장 구간별 계측 (EligibilityResultService.refreshAll)\n\n");
        sb.append("## 실행 환경\n\n| 항목 | 값 |\n|---|---|\n");
        Runtime rt = Runtime.getRuntime();
        row(sb, "측정 시각", LocalDateTime.now(KST).toString());
        row(sb, "JVM", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        row(sb, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        row(sb, "CPU 코어(JVM 인식)", String.valueOf(rt.availableProcessors()));
        row(sb, "최대 힙", (rt.maxMemory() / 1024 / 1024) + " MB");
        row(sb, "PostgreSQL", jdbc.queryForObject("SELECT version()", String.class));
        row(sb, "Hibernate", org.hibernate.Version.getVersionString());
        row(sb, "hibernate.jdbc.batch_size", String.valueOf(entityManagerFactory.getProperties().getOrDefault(
                "hibernate.jdbc.batch_size", "(설정 없음)")));
        BenchmarkEnvironment.describe(jdbc, resultService).forEach((k, v) -> row(sb, k, v));
        row(sb, "반복", "첫 refreshAll 워밍업 1 + 측정 " + coldIterations + ", 다시 refreshAll 워밍업 3 + 측정 " + warmIterations);
        row(sb, "기준 시각", NOW.toString());
        sb.append("\n시간 ms. SQL 수 = Hibernate가 준비한 SQL (호출 1회 기준).\n\n");

        sb.append("## 1. 동등성 검증 (운영 refreshAll vs 재현 경로, 첫 refreshAll 1회씩)\n\n");
        sb.append("| 정책 수 | 복잡도 | 운영 ms | 재현 ms | 운영 SQL (S/I/U) | 재현 SQL (S/I/U) | 준비 statement 운영/재현 | 저장 행 | 행 내용 일치 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (DatasetResult r : results) {
            Equivalence e = r.equivalence();
            sb.append(String.format("| %d | %s | %.1f | %.1f | %s | %s | %d / %d | %d | %s |%n", r.size(), r.complexity(),
                    e.operatingMillis(), e.replicaMillis(), sil(e.operatingSql()), sil(e.replicaSql()),
                    e.operatingPrepared(), e.replicaPrepared(), e.rows(), e.rowsEqual() ? "예" : "아니오"));
        }

        appendSegments(sb, "## 2. 첫 refreshAll 구간별 시간 (p50, 저장된 결과 없음)", results, true);
        appendSql(sb, "## 3. 첫 refreshAll 구간별 SQL 수 (SELECT/INSERT/UPDATE, 호출 1회)", results, true);
        appendSegments(sb, "## 4. 다시 refreshAll 구간별 시간 (p50, 모두 최신)", results, false);
        appendSql(sb, "## 5. 다시 refreshAll 구간별 SQL 수", results, false);

        sb.append("\n## 6. 변경 감지 실험 (persist 직후 flush에서 UPDATE가 나오는가)\n\n");
        sb.append("| 템플릿 | 조건 | 필드 | 결과 | 값 타입 | 1차 flush UPDATE | 2차 flush UPDATE |\n|---|---|---|---|---|---|---|\n");
        for (ExperimentRow x : experiment) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %d | %d |%n", x.template(), x.type(), x.field(),
                    x.result(), x.valueTypes().replace("|", "/"), x.updatesFirstFlush(), x.updatesSecondFlush()));
        }

        sb.append("\n## 7. SQL 표본\n\n");
        sb.append("첫 INSERT: `").append(abbreviate(StoreSqlCounter.firstInsert)).append("`\n\n");
        sb.append("첫 UPDATE: `").append(abbreviate(StoreSqlCounter.firstUpdate)).append("`\n");
        return sb.toString();
    }

    private void appendSegments(StringBuilder sb, String title, List<DatasetResult> results, boolean cold) {
        sb.append("\n").append(title).append("\n\n| 정책 수 | 복잡도 | 합계 p50 | 합계 p95 |");
        for (Seg s : Seg.values()) {
            sb.append(" ").append(s.label).append(" |");
        }
        sb.append("\n|---|---|---|---|").append("---|".repeat(Seg.values().length)).append("\n");
        for (DatasetResult r : results) {
            List<Run> runs = cold ? r.cold() : r.warm();
            Stat total = Stat.of(runs.stream().map(x -> x.totalNanos() / 1e6).toList());
            sb.append(String.format("| %d | %s | %.1f | %.1f |", r.size(), r.complexity(), total.p50(), total.p95()));
            for (Seg s : Seg.values()) {
                Stat st = Stat.of(runs.stream().map(x -> x.nanos[s.ordinal()] / 1e6).toList());
                sb.append(String.format(" %.1f (%.0f%%) |", st.p50(), total.p50() == 0 ? 0 : st.p50() * 100 / total.p50()));
            }
            sb.append("\n");
        }
    }

    private void appendSql(StringBuilder sb, String title, List<DatasetResult> results, boolean cold) {
        sb.append("\n").append(title).append("\n\n| 정책 수 | 복잡도 | 합계 |");
        for (Seg s : Seg.values()) {
            sb.append(" ").append(s.label).append(" |");
        }
        sb.append("\n|---|---|---|").append("---|".repeat(Seg.values().length)).append("\n");
        for (DatasetResult r : results) {
            Run run = (cold ? r.cold() : r.warm()).getLast();
            sb.append(String.format("| %d | %s | %s |", r.size(), r.complexity(), sil(run.totalSql())));
            for (Seg s : Seg.values()) {
                sb.append(" ").append(sil(run.sql[s.ordinal()])).append(" |");
            }
            sb.append("\n");
        }
    }

    private static String sil(Snapshot s) {
        String base = s.select() + "/" + s.insert() + "/" + s.update();
        return s.delete() + s.other() == 0 ? base : base + " (+" + (s.delete() + s.other()) + ")";
    }

    private static String abbreviate(String sql) {
        if (sql == null) {
            return "(없음)";
        }
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + " …" : oneLine;
    }

    private static void row(StringBuilder sb, String key, String value) {
        sb.append("| ").append(key).append(" | ").append(value.replace("|", "/")).append(" |\n");
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
