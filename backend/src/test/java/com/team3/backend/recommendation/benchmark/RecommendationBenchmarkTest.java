package com.team3.backend.recommendation.benchmark;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.domain.entity.EducationLevel;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.domain.entity.UserInterest;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.service.RecommendationCandidateService;
import com.team3.backend.recommendation.service.RecommendationInterestMatcher;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 실제 정규화 덤프 + 합성 사용자로 개인화 추천 API(GET /api/users/me/recommendations)의 비용을 측정한다 (개선 전후 공용).
 * 전용 PostgreSQL(perf-data/opportunity-recommendation/env)에만 연결하며 공고 테이블에는 쓰지 않는다.
 * <ul>
 *   <li>setup: S1~S4 합성 사용자·프로필·관심 분야 + 실제 판정 엔진(refreshAll) 결과 (Before와 같은 정의)</li>
 *   <li>setup-extra: 숨김이 많은 사용자(S5)와 동등성 검증용 사용자(E1~E4). S1~S4는 건드리지 않는다</li>
 *   <li>measure: 시나리오 교차 반복 요청 — 응답·SQL·JDBC·커넥션·할당 메모리·메서드별 시간·후보 단계별 건수</li>
 *   <li>equivalence: 모든 합성 사용자의 응답 본문과 select() 결과(배치 100·500·1000)를 파일로 남긴다</li>
 * </ul>
 * 측정 대상 코드의 배치 크기·숨김 조회 방식은 YON_RECO_BENCH_BATCH·YON_RECO_BENCH_HIDDEN(batch|once)로 알려 준다 (예상 SQL 수·집계용).
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=${YON_RECO_BENCH_DB_URL}",
        "spring.datasource.username=application_user",
        "spring.datasource.password=${YON_RECO_BENCH_DB_PASSWORD}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.connection-init-sql=SET search_path TO application, pg_catalog",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.default_schema=application",
        "logging.level.org.hibernate.SQL=OFF",
        "app.scheduling.enabled=false",
        "collection.schedule.enabled=false",
        "yon.normalization.condition-set-backfill.enabled=false"
})
@AutoConfigureMockMvc
@Import(RecoBenchProbe.Configuration.class)
@EnabledIfEnvironmentVariable(named = "YON_RECO_BENCH_DB_URL", matches = ".+")
class RecommendationBenchmarkTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate BENCH_DAY = LocalDate.parse(
            System.getenv().getOrDefault("YON_RECO_BENCH_TODAY", "2026-10-29"));
    /** 판정 기준 시각. validUntil은 다음 생일이라 같은 날 안에서는 결과가 같다 */
    private static final OffsetDateTime JUDGED_AT = BENCH_DAY.atTime(9, 0).atOffset(ZoneOffset.ofHours(9));
    /** 측정 대상 코드의 RecommendationService.CANDIDATE_BATCH_SIZE (집계·예상 SQL 계산용) */
    private static final int BATCH_SIZE = Integer.parseInt(System.getenv().getOrDefault("YON_RECO_BENCH_BATCH", "100"));
    private static final boolean HIDDEN_ONCE = "once".equals(System.getenv().getOrDefault("YON_RECO_BENCH_HIDDEN", "batch"));
    /** 측정 대상 코드가 DB 그룹별 상위 후보 방식인지 (YON_RECO_BENCH_MODE=top-per-group) */
    private static final boolean TOP_PER_GROUP = "top-per-group".equals(System.getenv("YON_RECO_BENCH_MODE"));
    private static final String RESIDENCE = "1168000000";
    private static final Path OUT = Path.of("build/reports/reco-bench");
    private static final String RANGE = "kakao_id between 9100000001 and 9100000099";

    private static final List<Interest> POPULAR = List.of(
            new Interest(InterestType.CATEGORY, "일자리"), new Interest(InterestType.SUB_CATEGORY, "취업"),
            new Interest(InterestType.SUB_CATEGORY, "정보통신"), new Interest(InterestType.DETAIL_CATEGORY, "정보기술"));
    /** Before(2026-10-29) 응답의 S1·S2 추천 ID. 동등성 사용자의 숨김·저장 대상 */
    private static final List<Long> S1_BEFORE_PICKS = List.of(127792L, 127722L, 127801L, 127800L, 127775L);
    private static final List<Long> S2_BEFORE_PICKS = List.of(128646L, 140432L, 140431L, 140430L, 140429L);

    /** 측정 시나리오 (S1~S4는 Before와 같은 사용자). kakaoId 9,100,000,001~ 는 벤치마크 전용 예약 범위 */
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("S1-no-interest", 9_100_000_001L, List.of(), true),
            new Scenario("S2-popular-interest", 9_100_000_002L, POPULAR, true),
            new Scenario("S3-rare-interest", 9_100_000_003L, List.of(
                    new Interest(InterestType.SUB_CATEGORY, "기숙사"), new Interest(InterestType.DETAIL_CATEGORY, "항공기제작")), true),
            new Scenario("S4-no-fresh-result", 9_100_000_004L, List.of(), false),
            new Scenario("S5-many-hidden", 9_100_000_005L, List.of(), true));
    /** 동등성 검증 전용 사용자 (측정하지 않음) */
    private static final List<Scenario> EQUIVALENCE_USERS = List.of(
            new Scenario("E1-expired-eligible", 9_100_000_011L, POPULAR, true),
            new Scenario("E2-profile-version-changed", 9_100_000_012L, List.of(), true),
            new Scenario("E3-saved-picks", 9_100_000_013L, POPULAR, true),
            new Scenario("E4-hidden-picks", 9_100_000_014L, POPULAR, true));

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EligibilityResultService resultService;
    @Autowired private RecommendationCandidateService candidateService;
    @Autowired private RecommendationInterestMatcher interestMatcher;
    @Autowired private EligibilityProvider eligibilityProvider;

    // ── setup ──

    /** S1~S4를 지우고 다시 만든다 (Before와 같은 정의, 반복해도 같은 상태) */
    @Test
    @EnabledIfEnvironmentVariable(named = "YON_RECO_BENCH_PHASE", matches = "setup")
    void setup() throws IOException {
        assertThat(LocalDate.now(KST)).as("판정 기준일").isEqualTo(BENCH_DAY);
        List<Scenario> base = SCENARIOS.subList(0, 4);
        deleteSyntheticUsers(base);
        Map<String, Long> userIds = createUsers(base);
        write("setup.md", judgeAndReport(base, userIds, "합성 사용자 setup (S1~S4)"));
    }

    /**
     * S5·E1~E4를 지우고 다시 만든다. S1~S4 행은 건드리지 않는다.
     * S5: 기준일 후보 중 ID가 5의 배수인 공고와 Before S1 추천 5건을 숨기고, 숨기지 않은 정책 후보 중 ID가 7의 배수인 공고를 저장.
     * E1: 최신 판정 중 ELIGIBLE을 기준일에 만료(validUntil=기준일)로 바꾼다 → ELIGIBLE 추천이 사라져야 한다.
     * E2: 판정 후 프로필 버전을 올린다 → 모든 판정이 오래된 것이 되어 빈 목록.
     * E3: Before S2 추천 5건을 저장 → saved=true. E4: Before S2 추천 5건을 숨김 → 다음 순위가 올라온다.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "YON_RECO_BENCH_PHASE", matches = "setup-extra")
    void setupExtra() throws IOException {
        assertThat(LocalDate.now(KST)).as("판정 기준일").isEqualTo(BENCH_DAY);
        List<Scenario> extra = new ArrayList<>(List.of(SCENARIOS.get(4)));
        extra.addAll(EQUIVALENCE_USERS);
        deleteSyntheticUsers(extra);
        Map<String, Long> userIds = createUsers(extra);
        String report = judgeAndReport(extra, userIds, "합성 사용자 setup-extra (S5, E1~E4)");

        Long s5 = userIds.get("S5-many-hidden");
        List<Long> candidates = candidateIds();
        Set<Long> hidden = candidates.stream().filter(id -> id % 5 == 0).collect(Collectors.toCollection(HashSet::new));
        hidden.addAll(S1_BEFORE_PICKS);
        List<Long> policies = jdbcTemplate.queryForList("select id from opportunities where opportunity_type = 'YOUTH_POLICY'", Long.class);
        Set<Long> policyIds = new HashSet<>(policies);
        List<Long> saved = candidates.stream().filter(id -> id % 7 == 0 && !hidden.contains(id) && policyIds.contains(id)).toList();
        insertPreferences(s5, hidden, false, true);
        insertPreferences(s5, saved, true, false);

        jdbcTemplate.update("update eligibility_set_results set valid_until = ? where user_id = ? and eligibility_status = 'ELIGIBLE'",
                BENCH_DAY, userIds.get("E1-expired-eligible"));
        jdbcTemplate.update("update eligibility_profiles set profile_version = profile_version + 1 where user_id = ?",
                userIds.get("E2-profile-version-changed"));
        insertPreferences(userIds.get("E3-saved-picks"), S2_BEFORE_PICKS, true, false);
        insertPreferences(userIds.get("E4-hidden-picks"), S2_BEFORE_PICKS, false, true);

        write("setup-extra.md", report + "\n## 선호 데이터\n\n- S5 숨김 " + hidden.size() + "건 (후보 " + candidates.size()
                + "건 중 ID%5==0 + Before S1 추천 5건), S5 저장 " + saved.size() + "건 (정책 후보 중 ID%7==0, 숨김 제외)\n"
                + "- E1: ELIGIBLE 판정 validUntil=" + BENCH_DAY + ", E2: 프로필 버전 +1, E3: S2 추천 5건 저장, E4: S2 추천 5건 숨김\n\n"
                + "## 합성 데이터 지문\n\n" + syntheticFingerprint() + "\n");
    }

    private Map<String, Long> createUsers(List<Scenario> scenarios) {
        Map<String, Long> userIds = new LinkedHashMap<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            Region residence = entityManager.getReference(Region.class, RESIDENCE);
            for (Scenario scenario : scenarios) {
                User account = User.builder().kakaoId(scenario.kakaoId).role(Role.USER).build();
                entityManager.persist(account);
                entityManager.persist(EligibilityProfile.builder().userId(account.getId())
                        .birthDate(LocalDate.of(1999, 3, 15)).residenceRegion(residence)
                        .employmentStatus(EmploymentStatus.UNEMPLOYED)
                        .educationLevel(EducationLevel.UNIVERSITY).build());
                for (Interest interest : scenario.interests) {
                    entityManager.persist(UserInterest.create(account, interest.type, interest.code));
                }
                userIds.put(scenario.name, account.getId());
            }
        });
        return userIds;
    }

    private String judgeAndReport(List<Scenario> scenarios, Map<String, Long> userIds, String title) {
        StringBuilder report = new StringBuilder("# ").append(title).append("\n\n- 판정 기준 시각: ").append(JUDGED_AT)
                .append("\n- 프로필(전원 동일): 1999-03-15생, 거주 ").append(RESIDENCE)
                .append("(강남구), UNEMPLOYED, UNIVERSITY, 그 밖의 항목 미입력\n\n")
                .append("| 사용자 | userId | kakaoId | 관심 분야 | refreshAll 판정 묶음 수 | 소요 ms |\n|---|---:|---:|---|---:|---:|\n");
        for (Scenario scenario : scenarios) {
            Long userId = userIds.get(scenario.name);
            int judged = 0;
            long started = System.nanoTime();
            if (scenario.judged) {
                judged = resultService.refreshAll(userId, JUDGED_AT);
                assertThat(judged).isPositive();
            }
            report.append("| ").append(scenario.name).append(" | ").append(userId).append(" | ").append(scenario.kakaoId)
                    .append(" | ").append(scenario.interests.stream().map(i -> i.type + "=" + i.code).collect(Collectors.joining(", ")))
                    .append(" | ").append(judged).append(" | ").append(ms(System.nanoTime() - started)).append(" |\n");
        }
        report.append("\n## 판정 결과 분포 (eligibility_set_results)\n\n```\n");
        jdbcTemplate.queryForList("select u.kakao_id, r.eligibility_status, count(*) n from eligibility_set_results r "
                        + "join users u on u.id = r.user_id where u." + RANGE + " group by 1, 2 order by 1, 2")
                .forEach(row -> report.append(row).append("\n"));
        return report.append("```\n").toString();
    }

    /** 기준일 추천 후보 ID (RecommendationCandidateRepository.findAfter와 같은 조건을 운영 빈으로 순회) */
    private List<Long> candidateIds() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> {
            List<Long> ids = new ArrayList<>();
            long cursor = 0;
            while (true) {
                List<RecommendationCandidate> batch = candidateService.getCandidatesAfter(cursor, 1_000, BENCH_DAY);
                if (batch.isEmpty()) {
                    return ids;
                }
                batch.forEach(candidate -> ids.add(candidate.opportunityId()));
                cursor = batch.getLast().opportunityId();
            }
        });
    }

    private void insertPreferences(Long userId, java.util.Collection<Long> opportunityIds, boolean saved, boolean hidden) {
        List<Object[]> rows = opportunityIds.stream().sorted()
                .map(id -> new Object[]{userId, id, saved, hidden}).toList();
        jdbcTemplate.batchUpdate("insert into user_opportunity_preferences (user_id, opportunity_id, is_saved, is_hidden, "
                + "saved_at, hidden_at, saved_version_id, created_at, updated_at) "
                + "select ?, ?, ?, ?, null, null, null, timestamp '" + BENCH_DAY + " 09:00', timestamp '" + BENCH_DAY + " 09:00'", rows);
        if (saved) {
            jdbcTemplate.update("update user_opportunity_preferences p set saved_at = p.created_at, saved_version_id = o.current_version_id "
                    + "from opportunities o where o.id = p.opportunity_id and p.user_id = ? and p.is_saved", userId);
        }
        if (hidden) {
            jdbcTemplate.update("update user_opportunity_preferences set hidden_at = created_at where user_id = ? and is_hidden", userId);
        }
    }

    private void deleteSyntheticUsers(List<Scenario> scenarios) {
        String kakaoIds = scenarios.stream().map(scenario -> String.valueOf(scenario.kakaoId)).collect(Collectors.joining(","));
        String ids = "(select id from users where kakao_id in (" + kakaoIds + "))";
        for (String table : List.of("eligibility_set_results", "user_opportunity_preferences", "user_interests",
                "user_behavior_events", "refresh_tokens")) {
            jdbcTemplate.update("delete from " + table + " where user_id in " + ids);
        }
        jdbcTemplate.update("delete from eligibility_profile_special_targets where profile_id in "
                + "(select id from eligibility_profiles where user_id in " + ids + ")");
        jdbcTemplate.update("delete from eligibility_profiles where user_id in " + ids);
        jdbcTemplate.update("delete from users where kakao_id in (" + kakaoIds + ")");
    }

    private String syntheticFingerprint() {
        return jdbcTemplate.queryForList("select 'users' t, count(*) n, md5(string_agg(concat_ws('|', id, kakao_id), '#' order by id)) h "
                        + "from users where " + RANGE + " "
                        + "union all select 'eligibility_profiles', count(*), md5(string_agg(concat_ws('|', p.user_id, p.profile_version), '#' order by p.user_id)) "
                        + "from eligibility_profiles p join users u on u.id = p.user_id where u." + RANGE + " "
                        + "union all select 'eligibility_set_results', count(*), md5(string_agg(concat_ws('|', r.user_id, r.condition_set_id, "
                        + "r.profile_version, r.rule_version, r.eligibility_status, r.valid_until), '#' order by r.user_id, r.condition_set_id)) "
                        + "from eligibility_set_results r join users u on u.id = r.user_id where u." + RANGE + " "
                        + "union all select 'user_interests', count(*), md5(string_agg(concat_ws('|', i.user_id, i.interest_type, i.interest_code), '#' "
                        + "order by i.user_id, i.interest_type, i.interest_code)) from user_interests i join users u on u.id = i.user_id "
                        + "where u." + RANGE + " "
                        + "union all select 'user_opportunity_preferences', count(*), md5(string_agg(concat_ws('|', p.user_id, p.opportunity_id, "
                        + "p.is_saved, p.is_hidden), '#' order by p.user_id, p.opportunity_id)) from user_opportunity_preferences p "
                        + "join users u on u.id = p.user_id where u." + RANGE)
                .stream().map(Object::toString).collect(Collectors.joining("\n"));
    }

    // ── equivalence ──

    /** 모든 합성 사용자의 HTTP 응답 본문과 select() 결과(배치 100·500·1000)를 남긴다. 버전 간 파일을 diff로 비교한다 */
    @Test
    @EnabledIfEnvironmentVariable(named = "YON_RECO_BENCH_PHASE", matches = "equivalence")
    void equivalence() throws Exception {
        assertThat(LocalDate.now(KST)).as("기준일").isEqualTo(BENCH_DAY);
        StringBuilder out = new StringBuilder();
        List<Scenario> users = new ArrayList<>(SCENARIOS);
        users.addAll(EQUIVALENCE_USERS);
        for (Scenario scenario : users) {
            Long userId = jdbcTemplate.queryForObject("select id from users where kakao_id = ?", Long.class, scenario.kakaoId);
            out.append("## ").append(scenario.name).append("\n").append(request(userId, false).body).append("\n");
        }
        write("equivalence.txt", out.toString());
    }

    // ── measure ──

    @Test
    @EnabledIfEnvironmentVariable(named = "YON_RECO_BENCH_PHASE", matches = "measure")
    void measure() throws Exception {
        assertThat(LocalDate.now(KST)).as("측정 기준일").isEqualTo(BENCH_DAY);
        String fingerprintBefore = syntheticFingerprint();
        List<Scenario> scenarios = SCENARIOS.stream().filter(scenario -> jdbcTemplate.queryForObject(
                "select count(*) from users where kakao_id = ?", Long.class, scenario.kakaoId) == 1).toList();
        Map<String, Long> userIds = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            userIds.put(scenario.name, jdbcTemplate.queryForObject("select id from users where kakao_id = ?",
                    Long.class, scenario.kakaoId));
        }

        Map<String, Funnel> funnels = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            funnels.put(scenario.name, funnel(userIds.get(scenario.name)));
        }

        int warmups = count("YON_RECO_BENCH_WARMUP", 2, 0);
        int iterations = count("YON_RECO_BENCH_ITERATIONS", 10, 1);
        Map<String, List<Sample>> samples = new LinkedHashMap<>();
        Map<String, RecoBenchProbe.Capture> traces = new LinkedHashMap<>();
        scenarios.forEach(scenario -> samples.put(scenario.name, new ArrayList<>()));
        long benchStarted = System.nanoTime();
        for (int round = 0; round < warmups + iterations; round++) {
            for (int offset = 0; offset < scenarios.size(); offset++) {
                Scenario scenario = scenarios.get((round + offset) % scenarios.size());
                boolean measured = round >= warmups;
                boolean trace = measured && !traces.containsKey(scenario.name);
                Sample sample = request(userIds.get(scenario.name), trace);
                System.out.printf("RECO_BENCH round=%d %s %s %.1fms sql=%d alloc=%.1fMB%n", round,
                        measured ? "measure" : "warmup", scenario.name, sample.responseNanos / 1e6,
                        sample.capture.executions.size(), sample.allocatedBytes / 1e6);
                if (measured) {
                    samples.get(scenario.name).add(sample);
                    if (trace) {
                        traces.put(scenario.name, sample.capture);
                    }
                }
            }
        }
        long benchNanos = System.nanoTime() - benchStarted;
        assertThat(LocalDate.now(KST)).as("측정 중 날짜가 바뀌면 후보가 달라진다").isEqualTo(BENCH_DAY);
        assertThat(syntheticFingerprint()).as("측정은 합성 데이터를 바꾸지 않는다").isEqualTo(fingerprintBefore);
        for (Scenario scenario : scenarios) {
            List<Sample> rows = samples.get(scenario.name);
            assertThat(rows).as("응답 본문은 반복마다 같다").allMatch(row -> row.body.equals(rows.getFirst().body));
            assertThat(rows).as("SQL 수는 반복마다 같다")
                    .allMatch(row -> row.capture.executions.size() == rows.getFirst().capture.executions.size());
        }
        writeReports(scenarios, userIds, funnels, samples, traces, warmups, iterations, benchNanos, fingerprintBefore);
    }

    private Sample request(Long userId, boolean trace) throws Exception {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        RecoBenchProbe.start(trace);
        long allocatedBefore = threads.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        try {
            var result = mockMvc.perform(get("/api/users/me/recommendations")
                    .with(user(new CustomUserDetails(userId, Role.USER, "reco-bench")))).andReturn();
            long responseNanos = System.nanoTime() - started;
            long allocated = threads.getThreadAllocatedBytes(threadId) - allocatedBefore;
            RecoBenchProbe.Capture capture = RecoBenchProbe.stop();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            String body = result.getResponse().getContentAsString();
            // 읽기 전용: SELECT 또는 쓰기가 없는 CTE 조회(그룹별 상위 후보)만 실행된다
            assertThat(capture.executions).allMatch(execution -> {
                String sql = execution.sql.stripLeading().toLowerCase();
                return sql.startsWith("select")
                        || (sql.startsWith("with") && !sql.matches("(?s).*\\b(insert|update|delete|merge)\\b.*"));
            });
            return new Sample(responseNanos, capture, body, allocated);
        } finally {
            RecoBenchProbe.stop();
        }
    }

    /** 운영 빈과 같은 순서로 단계별 후보 수를 센다 (측정 밖, 정렬·선정은 하지 않는다). 숨김은 버전과 무관하게 SQL로 읽는다 */
    private Funnel funnel(Long userId) {
        Set<Long> hidden = new HashSet<>(jdbcTemplate.queryForList(
                "select opportunity_id from user_opportunity_preferences where user_id = ? and is_hidden", Long.class, userId));
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> {
            Funnel funnel = new Funnel();
            funnel.hiddenTotal = hidden.size();
            var match = interestMatcher.load(userId);
            long cursor = 0;
            while (true) {
                List<RecommendationCandidate> batch = candidateService.getCandidatesAfter(cursor, BATCH_SIZE, BENCH_DAY);
                funnel.findAfterCalls++;
                if (batch.isEmpty()) {
                    break;
                }
                funnel.batches++;
                cursor = batch.getLast().opportunityId();
                funnel.explored += batch.size();
                batch.forEach(candidate -> funnel.exploredByType.merge(candidate.type(), 1, Integer::sum));
                if (batch.stream().anyMatch(candidate -> candidate.type() == OpportunityType.VOCATIONAL_TRAINING)) {
                    funnel.batchesWithTraining++;
                }
                List<RecommendationCandidate> visible = batch.stream()
                        .filter(candidate -> !hidden.contains(candidate.opportunityId())).toList();
                funnel.hiddenInCandidates += batch.size() - visible.size();
                List<RecommendationCandidate> matched = visible.stream()
                        .filter(candidate -> match.match(candidate).isPresent()).toList();
                funnel.interestMatched += matched.size();
                if (matched.isEmpty()) {
                    continue;
                }
                funnel.batchesWithMatch++;
                Map<Long, EligibilityStatus> statuses = eligibilityProvider.findFreshStatuses(userId,
                        matched.stream().map(RecommendationCandidate::opportunityId).toList(), BENCH_DAY);
                statuses.values().forEach(value -> funnel.freshByStatus.merge(value, 1, Integer::sum));
                matched.stream().filter(candidate -> {
                    EligibilityStatus value = statuses.get(candidate.opportunityId());
                    return value == EligibilityStatus.ELIGIBLE || value == EligibilityStatus.POSSIBLY_ELIGIBLE;
                }).forEach(candidate -> funnel.recommendableByType.merge(candidate.type(), 1, Integer::sum));
            }
            return funnel;
        });
    }

    // ── report ──

    private void writeReports(List<Scenario> scenarios, Map<String, Long> userIds, Map<String, Funnel> funnels,
                              Map<String, List<Sample>> samples, Map<String, RecoBenchProbe.Capture> traces,
                              int warmups, int iterations, long benchNanos, String fingerprint) throws IOException {
        StringBuilder summary = new StringBuilder("# 개인화 추천 측정 원자료\n\n");
        summary.append("- 라벨: ").append(System.getenv().getOrDefault("YON_RECO_BENCH_LABEL", "(없음)"))
                .append("\n- 측정 대상 코드: ").append(TOP_PER_GROUP ? "DB 그룹별 상위 후보 (전체 순회 없음)"
                        : "전체 순회, 배치 크기 " + BATCH_SIZE + ", 숨김 조회 " + (HIDDEN_ONCE ? "요청당 1회" : "배치마다"))
                .append("\n- 기준일(KST): ").append(BENCH_DAY)
                .append("\n- Java: ").append(System.getProperty("java.version")).append(", Spring Boot: ")
                .append(SpringBootVersion.getVersion()).append(", PostgreSQL: ")
                .append(jdbcTemplate.queryForObject("select version()", String.class))
                .append("\n- DB 설정: shared_buffers=").append(jdbcTemplate.queryForObject("show shared_buffers", String.class))
                .append(", jit=").append(jdbcTemplate.queryForObject("show jit", String.class))
                .append(", work_mem=").append(jdbcTemplate.queryForObject("show work_mem", String.class))
                .append("\n- JVM 최대 힙: ").append(Runtime.getRuntime().maxMemory() / 1_048_576).append(" MB")
                .append("\n- 공고 수: ").append(jdbcTemplate.queryForObject("select count(*) from opportunities", Long.class))
                .append("\n- 워밍업 ").append(warmups).append("라운드, 측정 ").append(iterations)
                .append("라운드. 라운드마다 시나리오 순서를 한 칸씩 회전. 단일 스레드 MockMvc(보안 필터 포함, 인증은 테스트 principal)")
                .append("\n- 전체 측정 경과: ").append(ms(benchNanos)).append(" ms")
                .append("\n- 응답 시간: MockMvc.perform~andReturn. JDBC: PreparedStatement.execute* 경과 합계(클라이언트 측). ")
                .append("커넥션 점유: DataSource.getConnection~close. 할당: 요청 스레드의 ThreadMXBean 할당 바이트. 메서드 시간: 빈 프록시에서 잰 포함 시간\n\n");

        summary.append("## 후보 단계별 건수 (집계 패스, 운영 빈 직접 호출)\n\n")
                .append("| 시나리오 | userId | findAfter 호출 | 배치 | 훈련 포함 배치 | 관심 매칭 배치 | 탐색 후보 | (정책/훈련) | 숨김 전체 | 후보 중 숨김 | 관심 매칭 | 최신 판정 상태(매칭 중) | 추천 가능(ELIGIBLE+POSSIBLY) 정책/훈련 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---|---|\n");
        for (Scenario scenario : scenarios) {
            Funnel f = funnels.get(scenario.name);
            summary.append("| ").append(scenario.name).append(" | ").append(userIds.get(scenario.name)).append(" | ")
                    .append(f.findAfterCalls).append(" | ").append(f.batches).append(" | ").append(f.batchesWithTraining)
                    .append(" | ").append(f.batchesWithMatch).append(" | ").append(f.explored).append(" | ")
                    .append(f.exploredByType.getOrDefault(OpportunityType.YOUTH_POLICY, 0)).append("/")
                    .append(f.exploredByType.getOrDefault(OpportunityType.VOCATIONAL_TRAINING, 0)).append(" | ")
                    .append(f.hiddenTotal).append(" | ").append(f.hiddenInCandidates).append(" | ")
                    .append(f.interestMatched).append(" | ").append(f.freshByStatus)
                    .append(" | ").append(f.recommendableByType.getOrDefault(OpportunityType.YOUTH_POLICY, 0)).append("/")
                    .append(f.recommendableByType.getOrDefault(OpportunityType.VOCATIONAL_TRAINING, 0)).append(" |\n");
        }

        summary.append("\n## 응답 시간·SQL·JDBC·커넥션·할당 (측정 라운드)\n\n")
                .append("| 시나리오 | n | 선정 건수 | 평균 ms | p50 | p95 | p99 | 최소 | 최대 | SQL 수 | 예상 SQL 수 | JDBC 합계 평균 ms | JDBC/응답 % | 커넥션 점유 평균 ms | 커넥션 수 | 요청당 할당 평균 MB |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Scenario scenario : scenarios) {
            List<Sample> rows = samples.get(scenario.name);
            double[] response = sorted(rows.stream().mapToDouble(row -> row.responseNanos / 1e6).toArray());
            double jdbc = rows.stream().mapToDouble(row -> jdbcNanos(row.capture) / 1e6).average().orElseThrow();
            double avg = Arrays.stream(response).average().orElseThrow();
            Funnel f = funnels.get(scenario.name);
            int selected = countItems(rows.getFirst().body);
            // 고정: 프로필 1 + 관심 2 + 마지막 빈 findAfter 1 (+ 숨김 1회), 선정이 있으면 저장 1 + 지역 4
            // 전체 순회 방식일 때만 계산한다 (DB 그룹별 상위 후보 방식은 "-")
            String expected = TOP_PER_GROUP ? "-" : String.valueOf(4 + (HIDDEN_ONCE ? 1 : f.batches) + (selected > 0 ? 5 : 0)
                    + 2L * f.batches + f.batchesWithTraining + f.batchesWithMatch);
            summary.append("| ").append(scenario.name).append(" | ").append(rows.size()).append(" | ").append(selected)
                    .append(" | ").append(fmt(avg)).append(" | ").append(fmt(percentile(response, .50)))
                    .append(" | ").append(fmt(percentile(response, .95))).append(" | ").append(fmt(percentile(response, .99)))
                    .append(" | ").append(fmt(response[0])).append(" | ").append(fmt(response[response.length - 1]))
                    .append(" | ").append(rows.getFirst().capture.executions.size()).append(" | ").append(expected)
                    .append(" | ").append(fmt(jdbc)).append(" | ").append(fmt(100 * jdbc / avg))
                    .append(" | ").append(fmt(rows.stream().mapToDouble(row -> row.capture.connectionNanos / 1e6).average().orElseThrow()))
                    .append(" | ").append(rows.getFirst().capture.connections)
                    .append(" | ").append(fmt(rows.stream().mapToDouble(row -> row.allocatedBytes / 1e6).average().orElseThrow()))
                    .append(" |\n");
        }

        summary.append("\n## SQL 종류별 (측정 라운드 평균: 횟수 / JDBC ms / 결과 행 수 / 최대 바인드 수)\n\n");
        for (Scenario scenario : scenarios) {
            List<Sample> rows = samples.get(scenario.name);
            Map<String, double[]> byType = new TreeMap<>();
            for (Sample row : rows) {
                for (RecoBenchProbe.Execution execution : row.capture.executions) {
                    double[] total = byType.computeIfAbsent(classify(execution.sql), ignored -> new double[4]);
                    total[0]++;
                    total[1] += execution.nanos / 1e6;
                    total[2] += execution.rows;
                    total[3] = Math.max(total[3], execution.sql.chars().filter(c -> c == '?').count());
                }
            }
            summary.append("### ").append(scenario.name)
                    .append("\n\n| 종류 | 횟수 | JDBC ms | 1회 평균 ms | 결과 행 | 최대 바인드 수 |\n|---|---:|---:|---:|---:|---:|\n");
            byType.forEach((type, total) -> summary.append("| ").append(type).append(" | ").append(fmt(total[0] / rows.size()))
                    .append(" | ").append(fmt(total[1] / rows.size())).append(" | ").append(fmt(total[1] / total[0]))
                    .append(" | ").append(fmt(total[2] / rows.size())).append(" | ").append((long) total[3]).append(" |\n"));
            summary.append("\n");
        }

        summary.append("## 메서드별 포함 시간 (측정 라운드 평균: 호출 수 / ms)\n\n");
        for (Scenario scenario : scenarios) {
            List<Sample> rows = samples.get(scenario.name);
            Map<String, double[]> methods = new LinkedHashMap<>();
            for (Sample row : rows) {
                row.capture.methods.forEach((name, total) -> {
                    double[] sum = methods.computeIfAbsent(name, ignored -> new double[2]);
                    sum[0] += total[0];
                    sum[1] += total[1] / 1e6;
                });
            }
            summary.append("### ").append(scenario.name).append("\n\n| 메서드 | 호출 | ms |\n|---|---:|---:|\n");
            methods.forEach((name, sum) -> summary.append("| ").append(name).append(" | ").append(fmt(sum[0] / rows.size()))
                    .append(" | ").append(fmt(sum[1] / rows.size())).append(" |\n"));
            summary.append("\n");
        }
        summary.append("## 합성 데이터 지문 (측정 전후 동일 단언)\n\n```\n").append(fingerprint).append("\n```\n\n");
        summary.append("## 응답 본문 (첫 측정 요청)\n\n");
        for (Scenario scenario : scenarios) {
            summary.append("### ").append(scenario.name).append("\n\n```json\n").append(samples.get(scenario.name).getFirst().body)
                    .append("\n```\n\n");
        }
        write("summary.md", summary.toString());

        StringBuilder csv = new StringBuilder("scenario,index,response_ms,sql_count,jdbc_ms,connection_ms,connections,allocated_mb");
        List<String> types = List.of("topPerGroup", "findAfter", "windows", "trainingSchedule", "hidden", "freshStatuses", "profile",
                "userExists", "interests", "saved", "region:versionRefs", "region:versions", "region:conditions",
                "region:regions", "regionDirectory", "other");
        types.forEach(type -> csv.append(",").append(type).append("_count,").append(type).append("_ms"));
        csv.append(",findAfter_rows\n");
        for (Scenario scenario : scenarios) {
            int index = 0;
            for (Sample row : samples.get(scenario.name)) {
                csv.append(scenario.name).append(",").append(index++).append(",").append(fmt(row.responseNanos / 1e6))
                        .append(",").append(row.capture.executions.size()).append(",").append(fmt(jdbcNanos(row.capture) / 1e6))
                        .append(",").append(fmt(row.capture.connectionNanos / 1e6)).append(",").append(row.capture.connections)
                        .append(",").append(fmt(row.allocatedBytes / 1e6));
                for (String type : types) {
                    List<RecoBenchProbe.Execution> ofType = row.capture.executions.stream()
                            .filter(execution -> classify(execution.sql).equals(type)).toList();
                    csv.append(",").append(ofType.size()).append(",")
                            .append(fmt(ofType.stream().mapToLong(execution -> execution.nanos).sum() / 1e6));
                }
                csv.append(",").append(row.capture.executions.stream().filter(execution -> classify(execution.sql).equals("findAfter"))
                        .mapToInt(execution -> execution.rows).sum()).append("\n");
            }
        }
        write("samples.csv", csv.toString());

        StringBuilder sql = new StringBuilder("# 첫 측정 요청의 SQL (같은 문장은 묶음, 바인드 값은 종류별 첫 실행·가장 느린 실행)\n");
        for (Scenario scenario : scenarios) {
            RecoBenchProbe.Capture capture = traces.get(scenario.name);
            sql.append("\n## ").append(scenario.name).append("\n");
            Map<String, List<RecoBenchProbe.Execution>> grouped = capture.executions.stream()
                    .collect(Collectors.groupingBy(execution -> execution.sql, LinkedHashMap::new, Collectors.toList()));
            grouped.forEach((text, executions) -> {
                RecoBenchProbe.Execution slowest = executions.stream()
                        .max(Comparator.comparingLong(execution -> execution.nanos)).orElseThrow();
                sql.append("\n### [").append(classify(text)).append("] x").append(executions.size())
                        .append(", 합계 ").append(fmt(executions.stream().mapToLong(e -> e.nanos).sum() / 1e6))
                        .append(" ms, 최대 ").append(fmt(slowest.nanos / 1e6)).append(" ms\n\n```sql\n")
                        .append(text.replaceAll("\\s+", " ").trim()).append("\n```\n- 첫 실행 바인드: ")
                        .append(executions.getFirst().parameters).append("\n- 가장 느린 실행 바인드: ")
                        .append(slowest.parameters).append("\n");
            });
        }
        write("sql-trace.md", sql.toString());
    }

    /** Hibernate가 만든 SQL을 호출 위치별로 분류한다 */
    static String classify(String sql) {
        // hibernate.default_schema 때문에 테이블 이름 앞에 스키마가 붙는다
        String s = sql.toLowerCase(Locale.ROOT).replace("application.", "");
        if (s.contains("row_number()")) {
            return "topPerGroup";
        }
        if (s.contains("from opportunities") && s.contains("personalization_status")) {
            return "findAfter";
        }
        if (s.contains("from application_windows")) {
            return "windows";
        }
        if (s.contains("from training_course_details")) {
            return "trainingSchedule";
        }
        if (s.contains("user_opportunity_preferences") && s.contains("hidden")) {
            return "hidden";
        }
        if (s.contains("user_opportunity_preferences") && s.contains("saved")) {
            return "saved";
        }
        if (s.contains("eligibility_set_results")) {
            return "freshStatuses";
        }
        if (s.contains("from eligibility_profiles")) {
            return "profile";
        }
        if (s.contains("from user_interests")) {
            return "interests";
        }
        if (s.contains("from users")) {
            return "userExists";
        }
        if (s.contains("from opportunity_versions")) {
            return "region:versions";
        }
        if (s.contains("from opportunity_conditions")) {
            return "region:conditions";
        }
        if (s.contains("from opportunity_regions")) {
            return "region:regions";
        }
        if (s.contains("from regions")) {
            return "regionDirectory";
        }
        if (s.contains("from opportunities")) {
            return "region:versionRefs";
        }
        return "other";
    }

    private static long jdbcNanos(RecoBenchProbe.Capture capture) {
        return capture.executions.stream().mapToLong(execution -> execution.nanos).sum();
    }

    private static int countItems(String body) {
        return body.equals("[]") ? 0 : body.split("\"opportunityId\"", -1).length - 1;
    }

    private static double[] sorted(double[] values) {
        Arrays.sort(values);
        return values;
    }

    private static double percentile(double[] sorted, double quantile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * quantile) - 1)];
    }

    private static int count(String name, int defaultValue, int minimum) {
        String value = System.getenv(name);
        int count = value == null ? defaultValue : Integer.parseInt(value);
        if (count < minimum) {
            throw new IllegalArgumentException(name + " must be >= " + minimum);
        }
        return count;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String ms(long nanos) {
        return fmt(nanos / 1e6);
    }

    private static void write(String name, String content) throws IOException {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name), content);
        System.out.println("RECO_BENCH_REPORT " + OUT.resolve(name).toAbsolutePath());
    }

    private record Interest(InterestType type, String code) {
    }

    private record Scenario(String name, long kakaoId, List<Interest> interests, boolean judged) {
    }

    private record Sample(long responseNanos, RecoBenchProbe.Capture capture, String body, long allocatedBytes) {
    }

    private static final class Funnel {
        int findAfterCalls;
        int batches;
        int batchesWithTraining;
        int batchesWithMatch;
        int explored;
        int hiddenTotal;
        int hiddenInCandidates;
        int interestMatched;
        final Map<OpportunityType, Integer> exploredByType = new EnumMap<>(OpportunityType.class);
        final Map<EligibilityStatus, Integer> freshByStatus = new EnumMap<>(EligibilityStatus.class);
        final Map<OpportunityType, Integer> recommendableByType = new EnumMap<>(OpportunityType.class);
    }
}
