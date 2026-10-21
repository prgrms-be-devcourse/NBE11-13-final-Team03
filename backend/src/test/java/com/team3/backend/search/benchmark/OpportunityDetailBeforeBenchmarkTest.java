package com.team3.backend.search.benchmark;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** PostgreSQL 상세 API의 최적화 전 SQL·응답시간을 독립적으로 측정한다. */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "collection.schedule.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.SQL=OFF"
})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfEnvironmentVariable(named = "YON_DETAIL_BEFORE_BENCH", matches = "true")
@org.springframework.context.annotation.Import(DetailSqlProbe.Configuration.class)
class OpportunityDetailBeforeBenchmarkTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("yon_detail_before").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private EligibilityProvider eligibilityProvider;

    private Long policyId;
    private Long trainingId;
    private Long freshUserId;
    private Long missingUserId;
    private Long expiredUserId;

    /** 고정 픽스처를 트랜잭션 밖에서 준비한 뒤 8개 조건을 교차 반복 측정한다. */
    @Test
    void measureBefore() throws Exception {
        createFixture();
        doAnswer(invocation -> {
            DetailSqlProbe.enterProvider();
            try {
                return invocation.callRealMethod();
            } finally {
                DetailSqlProbe.leaveProvider();
            }
        }).when(eligibilityProvider).findFreshEligibility(anyLong(), anyLong(), any(LocalDate.class));

        List<Scenario> scenarios = List.of(
                new Scenario("A 정책 비로그인", policyId, null, false),
                new Scenario("B 정책 최신", policyId, freshUserId, true),
                new Scenario("C 정책 미판정", policyId, missingUserId, false),
                new Scenario("D 정책 만료", policyId, expiredUserId, false),
                new Scenario("A 훈련 비로그인", trainingId, null, false),
                new Scenario("B 훈련 최신", trainingId, freshUserId, true),
                new Scenario("C 훈련 미판정", trainingId, missingUserId, false),
                new Scenario("D 훈련 만료", trainingId, expiredUserId, false));
        Map<String, List<Sample>> samples = new LinkedHashMap<>();
        Map<String, List<DetailSqlProbe.SqlExecution>> traces = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            samples.put(scenario.name, new ArrayList<>());
        }
        int warmups = repetitions("YON_DETAIL_BENCH_WARMUP", 20);
        int iterations = repetitions("YON_DETAIL_BENCH_ITERATIONS", 100);
        for (int round = 0; round < warmups + iterations; round++) {
            for (Scenario scenario : scenarios) {
                Sample sample = request(scenario);
                if (round >= warmups) {
                    samples.get(scenario.name).add(sample);
                    traces.putIfAbsent(scenario.name, sample.executions);
                }
            }
        }
        writeReport(scenarios, samples, traces, warmups, iterations);
    }

    private Sample request(Scenario scenario) throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        DetailSqlProbe.start();
        long started = System.nanoTime();
        try {
            var request = get("/api/opportunities/{opportunityId}", scenario.opportunityId);
            if (scenario.userId != null) {
                request.with(user(new CustomUserDetails(scenario.userId, Role.USER, "before-benchmark")));
            }
            var result = mockMvc.perform(request).andReturn();
            long responseNanos = System.nanoTime() - started;
            List<DetailSqlProbe.SqlExecution> executions = DetailSqlProbe.stop();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            String body = result.getResponse().getContentAsString();
            assertThat(body.contains("\"eligibility\":{")).isEqualTo(scenario.fresh);
            assertThat(executions).allMatch(sql -> sql.sql().trim().toLowerCase().startsWith("select"));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(executions.size());
            return new Sample(responseNanos, executions);
        } finally {
            DetailSqlProbe.stop();
        }
    }

    private void createFixture() {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            Region region = Region.builder().regionCode("1100000000").regionName("서울").build();
            entityManager.persist(region);
            Opportunity policy = opportunity("Before 정책", OpportunityType.YOUTH_POLICY);
            policyId = policy.getId();
            Opportunity training = opportunity("Before 훈련", OpportunityType.VOCATIONAL_TRAINING);
            trainingId = training.getId();
            LocalDate today = LocalDate.now(KST);
            entityManager.persist(ApplicationWindow.builder().version(policy.getCurrentVersion())
                    .periodType(PeriodType.FIXED).startDate(today.minusDays(5))
                    .endDate(today.plusDays(10)).validationStatus(ValidationStatus.VALID).build());
            entityManager.persist(ApplicationWindow.builder().version(training.getCurrentVersion())
                    .periodType(PeriodType.UNKNOWN).validationStatus(ValidationStatus.UNKNOWN).build());
            entityManager.persist(OpportunityCondition.builder().version(policy.getCurrentVersion())
                    .conditionType(ConditionType.AGE).requirementType(RequirementType.REQUIRED)
                    .conditionField("age").expression(Map.of("status", "RANGE", "min", 19, "max", 34))
                    .evidence(Map.of()).validationStatus(ValidationStatus.VALID).build());
            entityManager.persist(OpportunityRegion.builder().version(policy.getCurrentVersion())
                    .region(region).regionRole(RegionRole.RESIDENCE).build());
            entityManager.persist(OpportunityRegion.builder().version(training.getCurrentVersion())
                    .region(region).regionRole(RegionRole.IMPLEMENTATION).build());
            entityManager.persist(YouthPolicyDetail.builder().version(policy.getCurrentVersion())
                    .policyNo("BEFORE-1").description("측정용 정책").build());
            entityManager.persist(TrainingCourseDetail.builder().version(training.getCurrentVersion())
                    .courseId("BEFORE-2").courseRound(1).institutionName("측정용 기관")
                    .trainingStartDate(today.plusDays(20)).build());
            freshUserId = accountWithProfile(910001L, region);
            missingUserId = accountWithProfile(910002L, region);
            expiredUserId = accountWithProfile(910003L, region);
            for (Opportunity opportunity : List.of(policy, training)) {
                result(freshUserId, opportunity, today.plusDays(10));
                result(expiredUserId, opportunity, today);
            }
            entityManager.flush();
        });
    }

    private Opportunity opportunity(String title, OpportunityType type) {
        Opportunity opportunity = Opportunity.builder().title(title).opportunityType(type).build();
        entityManager.persist(opportunity);
        OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity).versionNo(1)
                .normalizationVersion("before").normalizedSnapshot(Map.of("title", title))
                .personalizationStatus(PersonalizationStatus.UNKNOWN).build();
        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
        return opportunity;
    }

    private Long accountWithProfile(Long kakaoId, Region region) {
        User account = User.builder().kakaoId(kakaoId).role(Role.USER).build();
        entityManager.persist(account);
        entityManager.persist(EligibilityProfile.builder().userId(account.getId())
                .birthDate(LocalDate.of(1998, 1, 1)).residenceRegion(region)
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY).build());
        return account.getId();
    }

    private void result(Long userId, Opportunity opportunity, LocalDate validUntil) {
        entityManager.persist(EligibilityResult.builder().userId(userId)
                .opportunityId(opportunity.getId()).opportunityVersionId(opportunity.getCurrentVersion().getId())
                .profileVersion(1L).ruleVersion(EligibilityResultService.RULE_VERSION)
                .eligibilityStatus(EligibilityStatus.ELIGIBLE).reason("측정용 판정")
                .conditionResults(List.of()).validUntil(validUntil).evaluatedAt(LocalDateTime.now()).build());
    }

    private void writeReport(List<Scenario> scenarios, Map<String, List<Sample>> samples,
                             Map<String, List<DetailSqlProbe.SqlExecution>> traces,
                             int warmups, int iterations) throws IOException {
        StringBuilder report = new StringBuilder("# 공고 상세 Before 측정 원자료\n\n");
        report.append("- Java: ").append(System.getProperty("java.version"))
                .append(", Spring Boot: ").append(SpringBootVersion.getVersion())
                .append(", PostgreSQL: ").append(jdbcTemplate.queryForObject("select version()", String.class))
                .append("\n- OS: ").append(System.getProperty("os.name"))
                .append(", JVM: ").append(System.getProperty("java.vm.name"))
                .append("\n- Testcontainers image: postgres:16-alpine, MockMvc in-process, 순차 단일 스레드")
                .append("\n- fixture: 공고/버전 각 2, 신청기간 2, 조건 1, 지역 1, 공고지역 2, 상세 각 1, 사용자/프로필 각 3, 판정 결과 4")
                .append("\n- 워밍업: 조건별 ").append(warmups).append("회, 본 측정: 조건별 ").append(iterations)
                .append("회 (8개 조건 교차 실행)\n")
                .append("- 응답 시간: MockMvc.perform 시작~andReturn 완료; SQL 시간: JDBC PreparedStatement.execute* 내부 벽시계 합계. 데이터 생성·통계 초기화·결과 검사 제외.\n")
                .append("- Provider SQL: MockitoSpyBean에서 findFreshEligibility 호출 전후로 구간 표시; 1차 캐시는 요청별 트랜잭션에서만 유효.\n")
                .append("- 중복 조회: 최신 판정에서 Provider 첫 판정결과 조회 뒤의 공고·신청기간·훈련일정 projection 3회. 비중은 해당 JDBC execute 시간 평균 / MockMvc 응답시간 평균이며 제거 시 개선폭이 아니다.\n")
                .append("- 측정 요청 중 SELECT 이외 SQL 0회 (테스트 단언); Hibernate prepareStatement 횟수와 JDBC 실행 횟수 동일 (테스트 단언).\n\n")
                .append("| 조건 | SQL 수 | Provider SQL 수 | 응답 평균 ms | p50 | p95 | p99 | SQL 평균 ms | Provider SQL 평균 ms | 중복 SQL 평균 ms | 응답 대비 % |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Scenario scenario : scenarios) {
            List<Sample> rows = samples.get(scenario.name);
            double[] response = rows.stream().mapToDouble(row -> row.responseNanos / 1_000_000.0).toArray();
            double[] sql = rows.stream().mapToDouble(row -> row.executions.stream()
                    .mapToLong(execution -> execution.nanos()).sum() / 1_000_000.0).toArray();
            double[] provider = rows.stream().mapToDouble(row -> row.executions.stream()
                    .filter(execution -> execution.provider()).mapToLong(execution -> execution.nanos())
                    .sum() / 1_000_000.0).toArray();
            double[] duplicated = rows.stream().mapToDouble(row -> row.executions.stream()
                    .filter(DetailSqlProbe.SqlExecution::provider).skip(1)
                    .mapToLong(DetailSqlProbe.SqlExecution::nanos).sum() / 1_000_000.0).toArray();
            Arrays.sort(response);
            double responseAverage = Arrays.stream(response).average().orElseThrow();
            double duplicateAverage = Arrays.stream(duplicated).average().orElseThrow();
            report.append("| ").append(scenario.name).append(" | ")
                    .append(rows.getFirst().executions.size()).append(" | ")
                    .append(rows.getFirst().executions.stream().filter(DetailSqlProbe.SqlExecution::provider).count())
                    .append(" | ").append(format(responseAverage))
                    .append(" | ").append(format(percentile(response, .50)))
                    .append(" | ").append(format(percentile(response, .95)))
                    .append(" | ").append(format(percentile(response, .99)))
                    .append(" | ").append(format(Arrays.stream(sql).average().orElseThrow()))
                    .append(" | ").append(format(Arrays.stream(provider).average().orElseThrow()))
                    .append(" | ").append(format(duplicateAverage))
                    .append(" | ").append(format(100 * duplicateAverage / responseAverage))
                    .append(" |\n");
            assertThat(rows).allMatch(row -> row.executions.size() == rows.getFirst().executions.size());
        }
        report.append("\n## 첫 측정 요청별 SQL (바인드 값 제외)\n");
        for (Scenario scenario : scenarios) {
            report.append("\n### ").append(scenario.name).append("\n\n");
            int index = 0;
            for (DetailSqlProbe.SqlExecution execution : traces.get(scenario.name)) {
                report.append(++index).append(". ")
                        .append(execution.provider() ? "[Provider] " : "[Detail] ")
                        .append(execution.sql().replaceAll("\\s+", " ").trim()).append("\n");
            }
        }
        Path path = Path.of("build/reports/opportunity-detail-before/raw.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, report.toString());
        System.out.println("Before benchmark: " + path.toAbsolutePath());
    }

    private static int repetitions(String name, int defaultValue) {
        String value = System.getenv(name);
        int count = value == null ? defaultValue : Integer.parseInt(value);
        if (count < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return count;
    }

    private static double percentile(double[] sorted, double quantile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * quantile) - 1)];
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private record Scenario(String name, Long opportunityId, Long userId, boolean fresh) {
    }

    private record Sample(long responseNanos, List<DetailSqlProbe.SqlExecution> executions) {
    }
}
