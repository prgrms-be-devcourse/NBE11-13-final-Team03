package com.team3.backend.eligibility.benchmark;

import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.eligibility.engine.EligibilityProfileInput;
import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshot;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshotPage;
import com.team3.backend.eligibility.api.local.LocalEligibilityProvider;
import com.team3.backend.eligibility.api.local.OpportunityInfoAssembler;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.eligibility.dto.EligibilityListResponse;
import com.team3.backend.eligibility.engine.ConditionEvaluation;
import com.team3.backend.eligibility.engine.EligibilityConditionInput;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.eligibility.service.EligibilityEvaluator;
import com.team3.backend.eligibility.service.EligibilityQueryService;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.dto.ApplicationWindowRow;
import com.team3.backend.opportunity.dto.ConditionRow;
import com.team3.backend.opportunity.dto.OpportunityVersionRef;
import com.team3.backend.opportunity.dto.RegionCodeRow;
import com.team3.backend.opportunity.normalization.dto.NormalizationCommand;
import com.team3.backend.opportunity.normalization.dto.YouthPolicyRaw;
import com.team3.backend.opportunity.normalization.*;
import com.team3.backend.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.backend.opportunity.normalization.official.OfficialSupplementer;
import com.team3.backend.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.backend.opportunity.service.YouthPolicyNormalizationService;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.region.domain.entity.Region;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판정 성능 측정: 조회 시점 계산(목록·전체 판정)과 판정 결과 저장·재사용(ELIGIBILITY_RESULT). 운영 코드는 건드리지 않는다.
 *
 * 실행 (일반 테스트와 분리된 전용 DB 사용, ddl-auto=create-drop):
 *   YON_BENCH_DB_URL=jdbc:postgresql://localhost:5432/yon_bench sh ./gradlew test --tests '*EligibilityBenchmarkTest'
 * 선택 환경변수: YON_BENCH_DB_USERNAME(postgres), YON_BENCH_DB_PASSWORD(1234),
 *   YON_BENCH_SIZES(1000,5000,10000), YON_BENCH_WARMUP(10), YON_BENCH_ITERATIONS(50),
 *   YON_BENCH_FULL_WARMUP(3), YON_BENCH_FULL_ITERATIONS(20)
 * 결과: build/reports/eligibility-benchmark/result-*.md
 *
 * 데이터 준비
 * 1. 단순·복합·불명확 템플릿 정책 6건을 실제 정규화 서비스로 정규화한다.
 * 2. 템플릿 버전의 조건·신청기간·지역 행을 SQL로 그대로 복제해 N건을 만든다 (jsonb 포함 바이트 단위 복제).
 * 3. 복제본이 템플릿과 행 단위로 같은지, 판정 결과가 같은지 검증한 뒤 측정한다.
 * 데이터 준비·검증 시간은 측정에서 제외한다.
 *
 * 측정
 * - 목록 판정: EligibilityQueryService.evaluateAll(운영 코드)의 첫 페이지 / 마지막 페이지, size 20·100
 * - 전체 판정: 한 사용자 × 전체 정책, 저장 없이 판정만. 운영 판정(EligibilityEvaluator)과 같은 Projection 조회
 *   (조건·거주지 지역·신청기간)와 refreshAll과 같은 1,000건 페이지 조회를 재현해 DB 조회와 엔진 시간을 나눠 잰다.
 * - 판정 결과 저장 (ELIGIBILITY_RESULT, 운영 코드 그대로):
 *   첫 refreshAll(저장된 결과 없음 → 전체 판정·저장), 다시 refreshAll(모두 최신 → 판정 0건),
 *   추천 후보 getEligibilities(후보 5건), 상태 필터 findByStatuses(0페이지, 20건).
 *   반복 횟수: YON_BENCH_RESULT_COLD_ITERATIONS(5), YON_BENCH_RESULT_WARM_ITERATIONS(20)
 * - 쿼리 수: Hibernate Statistics의 prepared statement 수.
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
        "logging.level.org.hibernate.SQL=OFF"
})
@Import({EligibilityResultService.class, LocalEligibilityProvider.class, OpportunityInfoAssembler.class,
        EligibilityQueryService.class, EligibilityEvaluator.class, EligibilityEngine.class, ApplicationStatusCalculator.class,
        YouthPolicyNormalizationService.class, AgeNormalizer.class, ApplicationPeriodNormalizer.class,
        RegionNormalizer.class, RegionDirectory.class, CodeConditionNormalizer.class, IncomeNormalizer.class,
        YouthPolicyCodeTable.class, OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
class EligibilityBenchmarkTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final long USER_ID = 1L;
    /** 복제본 ID 시작값 (템플릿 ID와 겹치지 않게) */
    private static final long CLONE_BASE = 1_000_000L;
    private static final int FULL_CHUNK = 1_000;
    private static final int[] PAGE_SIZES = {20, 100};

    enum Complexity { SIMPLE, COMPLEX, UNCLEAR, MIXED }

    /** 데이터셋 구성 패턴. MIXED = 단순 5 : 복합 3 : 불명확 2 */
    private static final Map<Complexity, List<String>> PATTERNS = Map.of(
            Complexity.SIMPLE, List.of("S1", "S2"),
            Complexity.COMPLEX, List.of("C1", "C2"),
            Complexity.UNCLEAR, List.of("U1", "U2"),
            Complexity.MIXED, List.of("S1", "S2", "C1", "S1", "U1", "S2", "C2", "S1", "U2", "C1"));

    @Autowired private EligibilityQueryService queryService;
    @Autowired private EligibilityResultService resultService;
    @Autowired private EligibilityProvider provider;
    @Autowired private OpportunityRepository opportunityRepository;
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
    private TransactionTemplate writeTx;
    private Statistics statistics;

    private record Template(String key, Complexity complexity, long opportunityId, long versionId) {
    }

    private record Stat(double p50, double p95, double min, double max) {
        static Stat of(List<Double> millis) {
            List<Double> s = new ArrayList<>(millis);
            Collections.sort(s);
            return new Stat(rank(s, 0.50), rank(s, 0.95), s.get(0), s.get(s.size() - 1));
        }

        private static double rank(List<Double> sorted, double p) {
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }

    private record ListResult(int pageSize, String page, int pageIndex, Stat stat, long queriesMin, long queriesMax) {
    }

    private record FullResult(Stat db, Stat engine, Stat total, long dbQueries, long engineQueries,
                              Map<EligibilityStatus, Long> distribution) {
    }

    private record ResultStoreResult(Stat cold, long coldQueries, Stat warm, long warmQueries,
                                     Stat candidates, long candidateQueries, Stat statusPage, long statusPageQueries,
                                     int savedRows) {
    }

    private record DatasetResult(int size, Complexity complexity, int conditionRows, int windowRows, int regionRows,
                                 List<ListResult> lists, FullResult full, ResultStoreResult store) {
    }

    @Test
    void 조회_시점_판정_성능_측정() throws IOException {
        jdbc = new JdbcTemplate(dataSource);
        readOnlyTx = new TransactionTemplate(transactionManager);
        readOnlyTx.setReadOnly(true);
        writeTx = new TransactionTemplate(transactionManager);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        List<Integer> sizes = intList("YON_BENCH_SIZES", "1000,5000,10000");
        int warmup = intEnv("YON_BENCH_WARMUP", 10);
        int iterations = intEnv("YON_BENCH_ITERATIONS", 50);
        int fullWarmup = intEnv("YON_BENCH_FULL_WARMUP", 3);
        int fullIterations = intEnv("YON_BENCH_FULL_ITERATIONS", 20);
        int coldIterations = intEnv("YON_BENCH_RESULT_COLD_ITERATIONS", 5);
        int warmIterations = intEnv("YON_BENCH_RESULT_WARM_ITERATIONS", 20);

        // ── 준비 (측정 제외) ──
        seedRegions();
        seedProfile();
        Map<String, Template> templates = normalizeTemplates();
        verifyTemplateCharacteristics(templates);
        // 템플릿은 복제 원본으로만 쓰고 목록·전체 판정 대상에서는 뺀다
        jdbc.update("UPDATE opportunities SET current_version_id = NULL WHERE id < ?", CLONE_BASE);

        List<DatasetResult> results = new ArrayList<>();
        for (int size : sizes) {
            for (Complexity complexity : Complexity.values()) {
                clearClones();
                Map<String, List<Long>> idsByTemplate = seedClones(templates, complexity, size);
                verifyClones(templates, idsByTemplate, size);
                jdbc.execute("VACUUM ANALYZE");
                int conditionRows = count("SELECT count(*) FROM opportunity_conditions WHERE version_id >= " + CLONE_BASE);
                int windowRows = count("SELECT count(*) FROM application_windows WHERE version_id >= " + CLONE_BASE);
                int regionRows = count("SELECT count(*) FROM opportunity_regions WHERE version_id >= " + CLONE_BASE);

                // ── 측정 ──
                List<ListResult> lists = new ArrayList<>();
                for (int pageSize : PAGE_SIZES) {
                    int lastPage = (size + pageSize - 1) / pageSize - 1;
                    lists.add(measureList(pageSize, "first", 0, size, warmup, iterations));
                    lists.add(measureList(pageSize, "last", lastPage, size, warmup, iterations));
                }
                FullResult full = measureFull(size, fullWarmup, fullIterations);
                ResultStoreResult store = measureResultStore(size, coldIterations, warmIterations);
                DatasetResult r = new DatasetResult(size, complexity, conditionRows, windowRows, regionRows, lists, full, store);
                results.add(r);
                System.out.printf("[benchmark] N=%d %s done%n", size, complexity);
            }
        }
        clearClones();

        String report = report(results, warmup, iterations, fullWarmup, fullIterations, coldIterations, warmIterations);
        Path dir = Path.of("build", "reports", "eligibility-benchmark");
        Files.createDirectories(dir);
        Path file = dir.resolve("result-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(file, report);
        System.out.println(report);
        System.out.println("[benchmark] report: " + file.toAbsolutePath());
    }

    // ───────────────────────── 측정 ─────────────────────────

    private ListResult measureList(int pageSize, String label, int pageIndex, int size, int warmup, int iterations) {
        for (int i = 0; i < warmup; i++) {
            queryService.evaluateAll(USER_ID, pageIndex, pageSize, NOW);
        }
        List<Double> millis = new ArrayList<>();
        long qMin = Long.MAX_VALUE;
        long qMax = 0;
        for (int i = 0; i < iterations; i++) {
            long q0 = statistics.getPrepareStatementCount();
            long t0 = System.nanoTime();
            EligibilityListResponse response = queryService.evaluateAll(USER_ID, pageIndex, pageSize, NOW);
            long t1 = System.nanoTime();
            long q = statistics.getPrepareStatementCount() - q0;
            millis.add((t1 - t0) / 1_000_000.0);
            qMin = Math.min(qMin, q);
            qMax = Math.max(qMax, q);
            if (i == 0) {
                assertThat(response.totalElements()).isEqualTo(size);
                int expectedItems = pageIndex == 0 ? Math.min(pageSize, size) : size - pageIndex * pageSize;
                assertThat(response.items()).hasSize(expectedItems);
            }
        }
        return new ListResult(pageSize, label, pageIndex, Stat.of(millis), qMin, qMax);
    }

    private FullResult measureFull(int size, int warmup, int iterations) {
        for (int i = 0; i < warmup; i++) {
            runFull(size);
        }
        List<Double> db = new ArrayList<>();
        List<Double> eng = new ArrayList<>();
        List<Double> total = new ArrayList<>();
        long dbQueries = 0;
        long engineQueries = 0;
        Map<EligibilityStatus, Long> distribution = null;
        for (int i = 0; i < iterations; i++) {
            FullRun run = runFull(size);
            db.add(run.dbNanos / 1_000_000.0);
            eng.add(run.engineNanos / 1_000_000.0);
            total.add((run.dbNanos + run.engineNanos) / 1_000_000.0);
            dbQueries = run.dbQueries;
            engineQueries = Math.max(engineQueries, run.engineQueries);
            distribution = run.distribution;
        }
        return new FullResult(Stat.of(db), Stat.of(eng), Stat.of(total), dbQueries, engineQueries, distribution);
    }

    private static final class FullRun {
        long dbNanos;
        long engineNanos;
        long dbQueries;
        long engineQueries;
        int evaluated;
        Map<EligibilityStatus, Long> distribution = new EnumMap<>(EligibilityStatus.class);
    }

    /**
     * 운영 판정(EligibilityEvaluator.judge)과 refreshAll의 조회 방식을 그대로 재현한다 (DB 조회와 엔진 실행을 나눠 재기 위해).
     * 조회: 프로필 → (현재 버전 1,000건 페이지 조회 → 조건·거주지 지역·신청기간 Projection IN 조회) 반복
     * 엔진: EligibilityEngine.evaluate → ApplicationStatusCalculator.calculate
     */
    private FullRun runFull(int size) {
        FullRun run = new FullRun();
        readOnlyTx.executeWithoutResult(status -> {
            long q0 = statistics.getPrepareStatementCount();
            long t0 = System.nanoTime();
            EligibilityProfile profile = profileRepository.findByUserId(USER_ID).orElseThrow();
            Hibernate.initialize(profile.getSpecialTargets());
            run.dbNanos += System.nanoTime() - t0;
            run.dbQueries += statistics.getPrepareStatementCount() - q0;

            int page = 0;
            Page<OpportunityVersionRef> refs;
            do {
                q0 = statistics.getPrepareStatementCount();
                t0 = System.nanoTime();
                refs = opportunityRepository.findAllCurrentVersionRefs(
                        PageRequest.of(page++, FULL_CHUNK, Sort.by(Sort.Direction.ASC, "id")));
                List<Long> versionIds = refs.getContent().stream().map(OpportunityVersionRef::versionId).toList();
                if (versionIds.isEmpty()) {
                    run.dbNanos += System.nanoTime() - t0;
                    run.dbQueries += statistics.getPrepareStatementCount() - q0;
                    break;
                }
                Map<Long, List<EligibilityConditionInput>> conditions = conditionRepository.findRowsByVersionIdIn(versionIds).stream()
                        .collect(Collectors.groupingBy(ConditionRow::versionId, Collectors.mapping(
                                c -> new EligibilityConditionInput(c.conditionType(), c.requirementType(), c.conditionField(),
                                        c.expression(), c.validationStatus()),
                                Collectors.toList())));
                Map<Long, List<String>> residence = opportunityRegionRepository
                        .findCodesByVersionIdInAndRole(versionIds, RegionRole.RESIDENCE).stream()
                        .collect(Collectors.groupingBy(RegionCodeRow::versionId,
                                Collectors.mapping(RegionCodeRow::regionCode, Collectors.toList())));
                Map<Long, List<ApplicationPeriod>> periods = windowRepository.findRowsByVersionIdIn(versionIds).stream()
                        .collect(Collectors.groupingBy(ApplicationWindowRow::versionId, Collectors.mapping(
                                w -> new ApplicationPeriod(w.periodType(), w.startDate(), w.endDate(),
                                        w.validationStatus(), w.periodText()),
                                Collectors.toList())));
                run.dbNanos += System.nanoTime() - t0;
                run.dbQueries += statistics.getPrepareStatementCount() - q0;

                q0 = statistics.getPrepareStatementCount();
                t0 = System.nanoTime();
                RegionIndex regionIndex = regionDirectory.index();
                EligibilityProfileInput input = EligibilityProfileInput.from(profile);
                for (Long versionId : versionIds) {
                    EligibilityEvaluation evaluation = engine.evaluate(input,
                            conditions.getOrDefault(versionId, List.of()),
                            residence.getOrDefault(versionId, List.of()),
                            regionIndex, TODAY);
                    ApplicationStatus applicationStatus = applicationStatusCalculator.calculate(
                            periods.getOrDefault(versionId, List.of()), TODAY);
                    run.distribution.merge(evaluation.status(), 1L, Long::sum);
                    if (applicationStatus == null) {
                        throw new IllegalStateException("신청 상태 계산 실패");
                    }
                    run.evaluated++;
                }
                run.engineNanos += System.nanoTime() - t0;
                run.engineQueries += statistics.getPrepareStatementCount() - q0;
                em.clear();
            } while (refs.hasNext());
        });
        assertThat(run.evaluated).isEqualTo(size);
        return run;
    }

    /**
     * 판정 결과 저장·재사용 (운영 코드 그대로). 결과는 조건 묶음 단위라, 복제본은 원본 템플릿의 묶음을 함께 쓴다.
     * cold: 저장된 결과를 지운 뒤 refreshAll → 조건 묶음 수만큼 판정 + 저장 (삭제 시간은 제외)
     * warm: 모두 최신인 상태에서 refreshAll → 최신 여부 확인만
     * candidates: 추천 후보 5건 getEligibilities (최신 결과 + 기회 요약)
     * statusPage: findByStatuses(ELIGIBLE, POSSIBLY_ELIGIBLE) 0페이지 20건 (refreshAll warm 포함)
     */
    private ResultStoreResult measureResultStore(int size, int coldIterations, int warmIterations) {
        int conditionSets = count("SELECT count(DISTINCT v.condition_set_id) FROM opportunities o "
                + "JOIN opportunity_versions v ON v.id = o.current_version_id");
        List<Double> cold = new ArrayList<>();
        long coldQueries = 0;
        for (int i = 0; i < coldIterations + 1; i++) {
            jdbc.update("DELETE FROM eligibility_set_results WHERE user_id = ?", USER_ID);
            long q0 = statistics.getPrepareStatementCount();
            long t0 = System.nanoTime();
            int judged = resultService.refreshAll(USER_ID, NOW);
            long t1 = System.nanoTime();
            assertThat(judged).isEqualTo(conditionSets);
            if (i > 0) {  // 첫 회는 워밍업
                cold.add((t1 - t0) / 1_000_000.0);
                coldQueries = statistics.getPrepareStatementCount() - q0;
            }
        }
        int savedRows = count("SELECT count(*) FROM eligibility_set_results WHERE user_id = " + USER_ID);

        List<Double> warm = new ArrayList<>();
        long warmQueries = 0;
        for (int i = 0; i < warmIterations + 3; i++) {
            long q0 = statistics.getPrepareStatementCount();
            long t0 = System.nanoTime();
            int judged = resultService.refreshAll(USER_ID, NOW);
            long t1 = System.nanoTime();
            assertThat(judged).isZero();
            if (i >= 3) {
                warm.add((t1 - t0) / 1_000_000.0);
                warmQueries = statistics.getPrepareStatementCount() - q0;
            }
        }

        List<Long> candidateIds = jdbc.queryForList(
                "SELECT id FROM opportunities WHERE current_version_id IS NOT NULL ORDER BY id DESC LIMIT 5", Long.class);
        List<Double> candidates = new ArrayList<>();
        long candidateQueries = 0;
        for (int i = 0; i < warmIterations + 3; i++) {
            long q0 = statistics.getPrepareStatementCount();
            long t0 = System.nanoTime();
            List<EligibilitySnapshot> snapshots = provider.getEligibilities(USER_ID, candidateIds);
            long t1 = System.nanoTime();
            assertThat(snapshots).hasSize(candidateIds.size());
            if (i >= 3) {
                candidates.add((t1 - t0) / 1_000_000.0);
                candidateQueries = statistics.getPrepareStatementCount() - q0;
            }
        }

        List<Double> statusPage = new ArrayList<>();
        long statusPageQueries = 0;
        for (int i = 0; i < warmIterations + 3; i++) {
            long q0 = statistics.getPrepareStatementCount();
            long t0 = System.nanoTime();
            EligibilitySnapshotPage page = provider.findByStatuses(USER_ID,
                    List.of(EligibilityStatus.ELIGIBLE, EligibilityStatus.POSSIBLY_ELIGIBLE), 0, 20);
            long t1 = System.nanoTime();
            assertThat(page.items().size()).isLessThanOrEqualTo(20);
            if (i >= 3) {
                statusPage.add((t1 - t0) / 1_000_000.0);
                statusPageQueries = statistics.getPrepareStatementCount() - q0;
            }
        }
        return new ResultStoreResult(Stat.of(cold), coldQueries, Stat.of(warm), warmQueries,
                Stat.of(candidates), candidateQueries, Stat.of(statusPage), statusPageQueries, savedRows);
    }

    // ───────────────────────── 데이터 준비 ─────────────────────────

    private void seedRegions() {
        writeTx.executeWithoutResult(s -> {
            Region seoul = region("1100000000", "서울특별시", null);
            region("1168000000", "강남구", seoul);
            region("1144000000", "마포구", seoul);
            region("1111000000", "종로구", seoul);
            Region gyeonggi = region("4100000000", "경기도", null);
            Region suwon = region("4111000000", "수원시", gyeonggi);
            region("4111100000", "장안구", suwon);
            region("4111300000", "권선구", suwon);
            region("4113000000", "성남시", gyeonggi);
            Region busan = region("2600000000", "부산광역시", null);
            region("2635000000", "해운대구", busan);
            region("2623000000", "부산진구", busan);
            Region daegu = region("2700000000", "대구광역시", null);
            region("2720000000", "남구", daegu);
            region("2711000000", "중구", daegu);
        });
        regionDirectory.refresh();
    }

    private Region region(String code, String name, Region parent) {
        return regionRepository.save(Region.builder().regionCode(code).regionName(name).parent(parent).build());
    }

    /** 대부분 항목을 입력한 사용자 (조건 비교가 실제로 일어나도록) */
    private void seedProfile() {
        writeTx.executeWithoutResult(s -> profileRepository.save(EligibilityProfile.builder()
                .userId(USER_ID)
                .birthDate(LocalDate.of(1998, 5, 15))
                .residenceRegion(regionRepository.getReferenceById("1168000000"))
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY)
                .enrollmentStatus(EnrollmentStatus.GRADUATED)
                .maritalStatus(MaritalStatus.SINGLE)
                .housingStatus(HousingStatus.MONTHLY_RENT)
                .homelessStatus(YesNoUnknown.YES)
                .personalIncome(IncomeAnswer.provided(new BigDecimal("2400000"), IncomePeriod.MONTHLY))
                .householdIncome(IncomeAnswer.provided(new BigDecimal("48000000"), IncomePeriod.YEARLY))
                .medianIncomeStatus(AnswerStatus.PROVIDED)
                .medianIncomePercent(new BigDecimal("120"))
                .studentLoanBracketStatus(AnswerStatus.PROVIDED)
                .studentLoanBracket(6)
                .householdSize(2)
                .majorField(MajorField.ENGINEERING)
                .specialTargetStatus(AnswerStatus.PROVIDED)
                .specialTargets(new HashSet<>(Set.of(SpecialTarget.WOMAN)))
                .militaryServiceStatus(MilitaryServiceStatus.NOT_APPLICABLE)
                .build()));
    }

    /** 템플릿 6건을 실제 정규화 서비스로 정규화한다 */
    private Map<String, Template> normalizeTemplates() {
        Map<String, YouthPolicyRaw> raws = new LinkedHashMap<>();
        // 단순: 나이 범위, 단일 지역, 단일 기간, 코드 전부 제한없음
        raws.put("S1", base("S1", "청년 월세 지원", "19", "34", "Y", "0057001", "20261001 ~ 20261031", "11680")
                .mrgSttsCd("0055003").jobCd("0013010").schoolCd("0049010").plcyMajorCd("0011009")
                .sBizCd("0014010").earnCndSeCd("0043001").build());
        raws.put("S2", base("S2", "청년 취업 상담", "19", "39", "Y", "0057002", null, "41111")
                .mrgSttsCd("0055003").jobCd("0013010").schoolCd("0049010").plcyMajorCd("0011009")
                .sBizCd("0014010").earnCndSeCd("0043001").build());
        // 복합: 여러 지역, 여러 기간, 구체 코드, 소득 기준, 추가 자격·참여 제한
        raws.put("C1", base("C1", "청년 주거 정착 지원", "19", "39", "Y", "0057001",
                "20260901 ~ 20260930, 20261101 ~ 20261130", "11680,11440,26350,27200")
                .mrgSttsCd("0055002").jobCd("0013003,0013006").schoolCd("0049005,0049006,0049007")
                .plcyMajorCd("0011005").sBizCd("0014002").earnCndSeCd("0043003")
                .earnEtcCn("가구 기준중위소득 150% 이하")
                .addAplyQlfcCndCn("공고일 기준 해당 지역에 6개월 이상 거주한 자")
                .ptcpPrpTrgtCn("타 기관 동일 사업 참여자 및 휴학생 제외").build());
        raws.put("C2", base("C2", "청년 학자금 이자 지원", "19", "39", "Y", "0057001",
                "20261010 ~ 20261020", "41111,41113,11110,26230")
                .mrgSttsCd("0055003").jobCd("0013001,0013003").schoolCd("0049007,0049008")
                .plcyMajorCd("0011001,0011002,0011003").sBizCd("0014001").earnCndSeCd("0043003")
                .earnEtcCn("학자금 지원구간 8구간 이하")
                .addAplyQlfcCndCn("대학 졸업 후 2년 이내인 자")
                .ptcpPrpTrgtCn("동일 목적의 정부 지원을 받는 자 제외").build());
        // 불명확: 나이 충돌, 사전에 없는 지역, 기타·미등록 코드, 단위 불명 소득 금액, 기간 불명
        raws.put("U1", base("U1", "청년 활동 지원", "0", "0", "Y", "0057001", null, "99999")
                .jobCd("0013009").schoolCd("0049009").plcyMajorCd("0011010").sBizCd("0014009")
                .earnCndSeCd("0043002").earnMaxAmt("3000").build());
        raws.put("U2", base("U2", "청년 역량 강화", "19", "34", "N", null, null, null)
                .mrgSttsCd("0055003,0055002")
                .addAplyQlfcCndCn("세부 자격은 공고문 참조").build());

        Map<String, Template> templates = new LinkedHashMap<>();
        for (Map.Entry<String, YouthPolicyRaw> e : raws.entrySet()) {
            normalizationService.normalize(new NormalizationCommand(1L, e.getValue(), List.of(),
                    "https://example.invalid/" + e.getKey(), LocalDateTime.of(2026, 10, 14, 9, 0)));
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT o.id, o.current_version_id FROM opportunities o "
                            + "JOIN opportunity_sources s ON s.opportunity_id = o.id WHERE s.external_id = ?", e.getKey());
            Complexity complexity = switch (e.getKey().charAt(0)) {
                case 'S' -> Complexity.SIMPLE;
                case 'C' -> Complexity.COMPLEX;
                default -> Complexity.UNCLEAR;
            };
            templates.put(e.getKey(), new Template(e.getKey(), complexity,
                    ((Number) row.get("id")).longValue(), ((Number) row.get("current_version_id")).longValue()));
        }
        return templates;
    }

    private static YouthPolicyRaw.YouthPolicyRawBuilder base(String no, String name, String min, String max, String limit,
                                                             String periodCode, String period, String zip) {
        return YouthPolicyRaw.builder().plcyNo(no).plcyNm(name)
                .sprtTrgtMinAge(min).sprtTrgtMaxAge(max).sprtTrgtAgeLmtYn(limit)
                .aplyPrdSeCd(periodCode).aplyYmd(period).zipCd(zip).lclsfNm("일자리");
    }

    /** 템플릿이 의도한 복잡도 특성을 실제로 갖는지 정규화 결과로 확인 */
    private void verifyTemplateCharacteristics(Map<String, Template> templates) {
        for (Template t : templates.values()) {
            List<String> statuses = jdbc.queryForList(
                    "SELECT validation_status FROM opportunity_conditions WHERE version_id = ? "
                            + "UNION ALL SELECT validation_status FROM application_windows WHERE version_id = ?",
                    String.class, t.versionId(), t.versionId());
            long notValid = statuses.stream().filter(s -> !"VALID".equals(s)).count();
            switch (t.complexity()) {
                case SIMPLE -> assertThat(notValid).as("%s: 단순 템플릿은 모든 조건·기간이 VALID", t.key()).isZero();
                case COMPLEX -> {
                    assertThat(count("SELECT count(*) FROM opportunity_conditions WHERE version_id = " + t.versionId()
                            + " AND requirement_type = 'EXCLUSION'")).as("%s: 제외 조건", t.key()).isPositive();
                    assertThat(count("SELECT count(*) FROM opportunity_conditions WHERE version_id = " + t.versionId()
                            + " AND condition_type = 'INCOME' AND validation_status = 'VALID'")).as("%s: 해석된 소득 조건", t.key()).isPositive();
                    assertThat(count("SELECT count(*) FROM opportunity_regions WHERE version_id = " + t.versionId()))
                            .as("%s: 복수 지역", t.key()).isGreaterThanOrEqualTo(2);
                }
                case UNCLEAR -> assertThat(notValid).as("%s: 불명확 템플릿은 VALID가 아닌 조건이 3개 이상", t.key())
                        .isGreaterThanOrEqualTo(3);
                default -> {
                }
            }
        }
    }

    private Map<String, List<Long>> seedClones(Map<String, Template> templates, Complexity complexity, int size) {
        List<String> pattern = PATTERNS.get(complexity);
        Map<String, List<Long>> ids = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            ids.computeIfAbsent(pattern.get(i % pattern.size()), k -> new ArrayList<>()).add(CLONE_BASE + i);
        }
        for (Map.Entry<String, List<Long>> e : ids.entrySet()) {
            Template t = templates.get(e.getKey());
            Long[] cloneIds = e.getValue().toArray(Long[]::new);
            cloneSql("INSERT INTO opportunities (id, opportunity_type, title, category, detail_url, current_version_id, created_at, updated_at) "
                    + "SELECT x.id, o.opportunity_type, o.title || ' #' || x.id, o.category, o.detail_url, NULL, now(), now() "
                    + "FROM opportunities o, unnest(?::bigint[]) AS x(id) WHERE o.id = ?", cloneIds, t.opportunityId());
            cloneSql("INSERT INTO opportunity_versions (id, opportunity_id, version_no, normalized_snapshot, personalization_status, normalization_version, condition_set_id, created_at) "
                    + "SELECT x.id, x.id, v.version_no, v.normalized_snapshot, v.personalization_status, v.normalization_version, v.condition_set_id, now() "
                    + "FROM opportunity_versions v, unnest(?::bigint[]) AS x(id) WHERE v.id = ?", cloneIds, t.versionId());
            cloneSql("INSERT INTO opportunity_conditions (version_id, condition_type, requirement_type, condition_field, expression, evidence, validation_status, created_at) "
                    + "SELECT x.id, c.condition_type, c.requirement_type, c.condition_field, c.expression, c.evidence, c.validation_status, now() "
                    + "FROM opportunity_conditions c, unnest(?::bigint[]) AS x(id) WHERE c.version_id = ? ORDER BY x.id, c.id", cloneIds, t.versionId());
            cloneSql("INSERT INTO application_windows (version_id, period_type, start_date, end_date, period_text, scope, validation_status) "
                    + "SELECT x.id, w.period_type, w.start_date, w.end_date, w.period_text, w.scope, w.validation_status "
                    + "FROM application_windows w, unnest(?::bigint[]) AS x(id) WHERE w.version_id = ? ORDER BY x.id, w.id", cloneIds, t.versionId());
            cloneSql("INSERT INTO opportunity_regions (version_id, region_code, region_role) "
                    + "SELECT x.id, r.region_code, r.region_role "
                    + "FROM opportunity_regions r, unnest(?::bigint[]) AS x(id) WHERE r.version_id = ? ORDER BY x.id, r.id", cloneIds, t.versionId());
        }
        jdbc.update("UPDATE opportunities SET current_version_id = id WHERE id >= ?", CLONE_BASE);
        return ids;
    }

    private void cloneSql(String sql, Long[] ids, long sourceId) {
        jdbc.update(sql, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids));
            ps.setLong(2, sourceId);
        });
    }

    private void clearClones() {
        // 데이터셋마다 처음부터 판정하도록 저장된 판정 결과도 지운다
        jdbc.update("DELETE FROM eligibility_set_results");
        jdbc.update("UPDATE opportunities SET current_version_id = NULL WHERE id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM opportunity_regions WHERE version_id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM application_windows WHERE version_id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM opportunity_conditions WHERE version_id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM opportunity_versions WHERE id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM opportunities WHERE id >= ?", CLONE_BASE);
    }

    /**
     * 복제본이 실제 정규화 결과(템플릿)와 같은 구조인지 검증한다.
     * 1. 전체 행 수 = Σ(템플릿 행 수 × 복제 수)
     * 2. 템플릿마다 첫·중간·마지막 복제본의 조건·신청기간·지역 행이 템플릿과 같음 (jsonb 포함)
     * 3. 같은 복제본의 엔진 판정 결과(최종 + 조건별)가 템플릿과 같음
     */
    private void verifyClones(Map<String, Template> templates, Map<String, List<Long>> idsByTemplate, int size) {
        assertThat(count("SELECT count(*) FROM opportunities WHERE current_version_id IS NOT NULL")).isEqualTo(size);
        for (String table : List.of("opportunity_conditions", "application_windows", "opportunity_regions")) {
            long expected = 0;
            for (Map.Entry<String, List<Long>> e : idsByTemplate.entrySet()) {
                expected += (long) count("SELECT count(*) FROM " + table + " WHERE version_id = "
                        + templates.get(e.getKey()).versionId()) * e.getValue().size();
            }
            assertThat((long) count("SELECT count(*) FROM " + table + " WHERE version_id >= " + CLONE_BASE))
                    .as("%s 행 수", table).isEqualTo(expected);
        }
        for (Map.Entry<String, List<Long>> e : idsByTemplate.entrySet()) {
            Template t = templates.get(e.getKey());
            List<Long> ids = e.getValue();
            for (Long cloneId : List.of(ids.get(0), ids.get(ids.size() / 2), ids.get(ids.size() - 1))) {
                assertThat(rows(cloneId)).as("%s 복제본 %d 행", t.key(), cloneId).isEqualTo(rows(t.versionId()));
                assertThat(judge(cloneId)).as("%s 복제본 %d 판정", t.key(), cloneId).isEqualTo(judge(t.versionId()));
            }
        }
    }

    private List<Map<String, Object>> rows(long versionId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT 'C' AS k, condition_type, requirement_type, condition_field, "
                + "expression::text AS expression, evidence::text AS evidence, validation_status "
                + "FROM opportunity_conditions WHERE version_id = ? ORDER BY 2, 3, 4, 5", versionId));
        rows.addAll(jdbc.queryForList("SELECT 'W' AS k, period_type, start_date, end_date, period_text, "
                + "scope::text AS scope, validation_status FROM application_windows WHERE version_id = ? ORDER BY 2, 3, 4", versionId));
        rows.addAll(jdbc.queryForList("SELECT 'R' AS k, region_code, region_role "
                + "FROM opportunity_regions WHERE version_id = ? ORDER BY 2, 3", versionId));
        return rows;
    }

    private List<String> judge(long versionId) {
        return readOnlyTx.execute(s -> {
            EligibilityProfile profile = profileRepository.findByUserId(USER_ID).orElseThrow();
            List<EligibilityConditionInput> conditions = conditionRepository.findByVersionId(versionId).stream()
                    .map(EligibilityConditionInput::from).toList();
            List<String> residence = opportunityRegionRepository.findByVersionId(versionId).stream()
                    .filter(r -> r.getRegionRole() == RegionRole.RESIDENCE)
                    .map(r -> r.getRegion().getRegionCode()).toList();
            EligibilityEvaluation evaluation = engine.evaluate(EligibilityProfileInput.from(profile), conditions, residence, regionDirectory.index(), TODAY);
            List<String> result = new ArrayList<>();
            result.add("FINAL=" + evaluation.status());
            evaluation.conditions().stream()
                    .map((ConditionEvaluation c) -> c.type() + "|" + c.field() + "|" + c.requirement() + "|" + c.result())
                    .sorted()
                    .forEach(result::add);
            return result;
        });
    }

    // ───────────────────────── 보고 ─────────────────────────

    private String report(List<DatasetResult> results, int warmup, int iterations, int fullWarmup, int fullIterations,
                          int coldIterations, int warmIterations) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 판정 성능 측정 결과 (조회 시점 계산 + 판정 결과 저장)\n\n");
        sb.append("## 실행 환경\n\n");
        sb.append("| 항목 | 값 |\n|---|---|\n");
        Runtime rt = Runtime.getRuntime();
        row(sb, "측정 시각", LocalDateTime.now(KST).toString());
        row(sb, "JVM", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        row(sb, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        row(sb, "CPU 코어(JVM 인식)", String.valueOf(rt.availableProcessors()));
        row(sb, "최대 힙", (rt.maxMemory() / 1024 / 1024) + " MB");
        BenchmarkEnvironment.describe(jdbc, resultService).forEach((k, v) -> row(sb, k, v));
        row(sb, "PostgreSQL", jdbc.queryForObject("SELECT version()", String.class));
        row(sb, "Spring Boot", SpringBootVersion.getVersion());
        row(sb, "Hibernate", org.hibernate.Version.getVersionString());
        row(sb, "목록 판정 반복", "워밍업 " + warmup + "회 + 측정 " + iterations + "회");
        row(sb, "전체 판정 반복", "워밍업 " + fullWarmup + "회 + 측정 " + fullIterations + "회");
        row(sb, "판정 결과 저장 반복", "첫 refreshAll 워밍업 1회 + 측정 " + coldIterations + "회, 나머지 워밍업 3회 + 측정 " + warmIterations + "회");
        row(sb, "기준 시각", NOW.toString());
        row(sb, "데이터 구성", "MIXED = 단순 5 : 복합 3 : 불명확 2 (ID 순으로 섞음)");
        sb.append("\n시간 단위는 ms, 쿼리 수는 호출 1회당 prepared statement 수입니다.\n\n");

        sb.append("## 데이터셋\n\n| 정책 수 | 복잡도 | 조건 행 | 신청기간 행 | 지역 행 | 전체 판정 분포 |\n|---|---|---|---|---|---|\n");
        for (DatasetResult r : results) {
            sb.append(String.format("| %d | %s | %d | %d | %d | %s |%n", r.size(), r.complexity(), r.conditionRows(),
                    r.windowRows(), r.regionRows(), r.full().distribution()));
        }

        sb.append("\n## 목록 판정 (EligibilityQueryService.evaluateAll)\n\n");
        sb.append("| 정책 수 | 복잡도 | size | 페이지 | p50 | p95 | 최소 | 최대 | 쿼리 수 |\n|---|---|---|---|---|---|---|---|---|\n");
        for (DatasetResult r : results) {
            for (ListResult l : r.lists()) {
                sb.append(String.format("| %d | %s | %d | %s (%d) | %.2f | %.2f | %.2f | %.2f | %s |%n",
                        r.size(), r.complexity(), l.pageSize(), l.page(), l.pageIndex(), l.stat().p50(), l.stat().p95(),
                        l.stat().min(), l.stat().max(),
                        l.queriesMin() == l.queriesMax() ? String.valueOf(l.queriesMin()) : l.queriesMin() + "~" + l.queriesMax()));
            }
        }

        sb.append("\n## 전체 판정 (한 사용자 × 전체 정책, 저장 없음, 운영과 같은 Projection 조회·1,000건 페이지)\n\n");
        sb.append("| 정책 수 | 복잡도 | DB p50 | DB p95 | 엔진 p50 | 엔진 p95 | 합계 p50 | 합계 p95 | DB 쿼리 수 | 엔진 구간 쿼리 수 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (DatasetResult r : results) {
            FullResult f = r.full();
            sb.append(String.format("| %d | %s | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %d | %d |%n",
                    r.size(), r.complexity(), f.db().p50(), f.db().p95(), f.engine().p50(), f.engine().p95(),
                    f.total().p50(), f.total().p95(), f.dbQueries(), f.engineQueries()));
        }

        sb.append("\n## 판정 결과 저장·재사용 (EligibilityResultService, EligibilityProvider)\n\n");
        sb.append("첫 refreshAll = 저장된 결과 없음 → 전체 판정·저장. 다시 refreshAll = 모두 최신 → 판정 0건. ");
        sb.append("후보 5건 = getEligibilities(최신 결과 + 기회 요약). 상태 필터 = findByStatuses 0페이지 20건(refreshAll 포함).\n\n");
        sb.append("| 정책 수 | 복잡도 | 저장 행 | 첫 refreshAll p50 | p95 | 쿼리 | 다시 refreshAll p50 | p95 | 쿼리 | 후보 5건 p50 | p95 | 쿼리 | 상태 필터 p50 | p95 | 쿼리 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (DatasetResult r : results) {
            ResultStoreResult x = r.store();
            sb.append(String.format("| %d | %s | %d | %.2f | %.2f | %d | %.2f | %.2f | %d | %.2f | %.2f | %d | %.2f | %.2f | %d |%n",
                    r.size(), r.complexity(), x.savedRows(),
                    x.cold().p50(), x.cold().p95(), x.coldQueries(),
                    x.warm().p50(), x.warm().p95(), x.warmQueries(),
                    x.candidates().p50(), x.candidates().p95(), x.candidateQueries(),
                    x.statusPage().p50(), x.statusPage().p95(), x.statusPageQueries()));
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, String key, String value) {
        sb.append("| ").append(key).append(" | ").append(value.replace("|", "/")).append(" |\n");
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
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
