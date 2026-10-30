package com.team3.backend.recommendation.benchmark;

import org.junit.jupiter.api.Tag;

import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import com.team3.backend.recommendation.service.RecommendationCandidateService;
import com.team3.backend.recommendation.service.RecommendationEligibilityService;
import com.team3.backend.recommendation.service.RecommendationFullScanReference;
import com.team3.backend.recommendation.service.RecommendationInterestMatcher;
import com.team3.backend.recommendation.service.RecommendationSelectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 덤프·합성 사용자 9명에서 운영 선정(DB 그룹별 상위 후보)과 전체 순회 참조 구현의 결과를 비교한다 (후보 전체 필드·관심 적합도·신청 가능성).
 * 전용 DB(perf-data/opportunity-recommendation/env)에만 연결하며 읽기만 한다.
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
        "app.scheduling.enabled=false",
        "collection.schedule.enabled=false",
        "yon.normalization.condition-set-backfill.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "YON_RECO_BENCH_PHASE", matches = "dump-reference")
@Tag("manual")
class RecommendationDumpReferenceTest {

    private static final LocalDate TODAY = LocalDate.parse(System.getenv().getOrDefault("YON_RECO_BENCH_TODAY", "2026-10-29"));
    private static final Map<String, Long> USERS = new LinkedHashMap<>();

    static {
        USERS.put("S1-no-interest", 9_100_000_001L);
        USERS.put("S2-popular-interest", 9_100_000_002L);
        USERS.put("S3-rare-interest", 9_100_000_003L);
        USERS.put("S4-no-fresh-result", 9_100_000_004L);
        USERS.put("S5-many-hidden", 9_100_000_005L);
        USERS.put("E1-expired-eligible", 9_100_000_011L);
        USERS.put("E2-profile-version-changed", 9_100_000_012L);
        USERS.put("E3-saved-picks", 9_100_000_013L);
        USERS.put("E4-hidden-picks", 9_100_000_014L);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RecommendationSelectionService selectionService;
    @Autowired private EligibilityProfileService profileService;
    @Autowired private RecommendationCandidateService candidateService;
    @Autowired private RecommendationInterestMatcher interestMatcher;
    @Autowired private RecommendationEligibilityService eligibilityService;
    @Autowired private PreferenceService preferenceService;

    @Test
    void productionSelectionMatchesFullScanReference() throws Exception {
        var reference = new RecommendationFullScanReference(profileService, candidateService, interestMatcher,
                eligibilityService, preferenceService);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        StringBuilder report = new StringBuilder("# 덤프 사용자 9명: 운영 선정 vs 전체 순회 참조 구현 (기준일 ").append(TODAY)
                .append(")\n\n| 사용자 | 선정 | 동일 | 참조 ms | 운영 ms | 운영 결과 |\n|---|---:|---|---:|---:|---|\n");
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, Long> user : USERS.entrySet()) {
            Long userId = jdbcTemplate.queryForObject("select id from users where kakao_id = ?", Long.class, user.getValue());
            long started = System.nanoTime();
            List<RecommendationEligibleCandidate> expected = readOnly.execute(status -> reference.select(userId, 100, TODAY));
            double referenceMs = (System.nanoTime() - started) / 1e6;
            started = System.nanoTime();
            List<RecommendationEligibleCandidate> actual = selectionService.select(userId, TODAY);
            double productionMs = (System.nanoTime() - started) / 1e6;
            boolean same = expected.equals(actual);
            if (!same) {
                mismatches.add(user.getKey() + "\n  expected=" + expected + "\n  actual  =" + actual);
            }
            report.append("| ").append(user.getKey()).append(" | ").append(actual.size()).append(" | ").append(same ? "O" : "X")
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", referenceMs))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", productionMs)).append(" | ")
                    .append(actual.stream().map(item -> item.candidate().opportunityId() + ":" + item.interestMatchLevel()
                            + ":" + item.eligibilityStatus()).collect(Collectors.joining(","))).append(" |\n");
        }
        report.append("\n").append(String.join("\n", mismatches)).append("\n");
        Path out = Path.of("build/reports/reco-bench/dump-reference.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println(report);
        assertThat(mismatches).isEmpty();
    }
}
