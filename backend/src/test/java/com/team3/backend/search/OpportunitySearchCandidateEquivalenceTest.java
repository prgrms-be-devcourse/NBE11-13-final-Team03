package com.team3.backend.search;

import com.team3.backend.search.dto.OpportunitySearchItemDto;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
import com.team3.backend.search.dto.OpportunitySearchSort;
import com.team3.backend.search.service.OpportunitySearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 후보 추출(B1)을 실제 공고 복원본에서 SQL 프로토타입과 비교하기 위한 수동 실행 테스트.
 * 기본 테스트에서는 건너뛴다. 실험용 DB에 읽기 전용(default_transaction_read_only)으로만 접속하고 스키마를 만들지 않는다.
 *
 *   YON_SEARCH_EQUIVALENCE_DB_URL      jdbc:postgresql://127.0.0.1:5433/<실험 DB>?currentSchema=application
 *   YON_SEARCH_EQUIVALENCE_DB_USER / YON_SEARCH_EQUIVALENCE_DB_PASSWORD
 *   YON_SEARCH_EQUIVALENCE_QUERIES     qid<TAB>검색어 파일
 *   YON_SEARCH_EQUIVALENCE_OUTPUT      결과 파일 (qid|id 전체 후보, 이어서 count|qid|totalElements)
 * 결과 파일은 프로토타입이 같은 형식으로 낸 후보와 외부에서 비교한다.
 */
@ActiveProfiles("test")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "YON_SEARCH_EQUIVALENCE_DB_URL", matches = ".+")
class OpportunitySearchCandidateEquivalenceTest {

    private static final int PAGE_SIZE = 100;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("YON_SEARCH_EQUIVALENCE_DB_URL");
        registry.add("spring.datasource.url", () -> url
                + (url.contains("?") ? "&" : "?") + "options=-c%20default_transaction_read_only=on");
        registry.add("spring.datasource.username", () -> System.getenv("YON_SEARCH_EQUIVALENCE_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("YON_SEARCH_EQUIVALENCE_DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private OpportunitySearchService searchService;

    @Test
    void writeCandidatesForEveryQuery() throws Exception {
        List<String> lines = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        for (String row : Files.readAllLines(Path.of(System.getenv("YON_SEARCH_EQUIVALENCE_QUERIES")),
                StandardCharsets.UTF_8)) {
            if (row.isBlank()) {
                continue;
            }
            String[] parts = row.split("\t", 2);
            if (System.getenv("YON_SEARCH_EQUIVALENCE_CAPTURE") != null) {
                // SQL 로그 수집용: 측정과 같은 첫 페이지(20건)만 한 번 호출한다
                searchService.search(parts[1], null, null, null, null, null, null, null, null, null, null, null,
                        null, OpportunitySearchSort.LATEST, 0, 20);
                continue;
            }
            long total = -1;
            int collected = 0;
            for (int page = 0; ; page++) {
                OpportunitySearchResponseDto response = searchService.search(parts[1], null, null, null, null,
                        null, null, null, null, null, null, null, null, OpportunitySearchSort.LATEST,
                        page, PAGE_SIZE);
                total = response.totalElements();
                for (OpportunitySearchItemDto item : response.content()) {
                    lines.add(parts[0] + "|" + item.opportunityId());
                    collected++;
                }
                if (!response.hasNext()) {
                    break;
                }
            }
            assertThat(collected).as(parts[1]).isEqualTo(total);
            counts.add("count|" + parts[0] + "|" + total);
            if (System.getenv("YON_SEARCH_EQUIVALENCE_TIMING") != null) {
                counts.add("time|" + parts[0] + "|" + firstPageMillis(parts[1]));
            }
        }
        lines.addAll(counts);
        Files.write(Path.of(System.getenv("YON_SEARCH_EQUIVALENCE_OUTPUT")), lines, StandardCharsets.UTF_8);
    }

    /** 첫 페이지(목록 20건 + COUNT) 호출 시간. 예열 2회 뒤 20회의 p50/p95 (ms) */
    private String firstPageMillis(String keyword) {
        List<Long> samples = new ArrayList<>();
        for (int run = 0; run < 22; run++) {
            long start = System.nanoTime();
            searchService.search(keyword, null, null, null, null, null, null, null, null, null, null, null, null,
                    OpportunitySearchSort.LATEST, 0, 20);
            if (run >= 2) {
                samples.add((System.nanoTime() - start) / 1_000_000);
            }
        }
        samples.sort(null);
        return samples.get(9) + "|" + samples.get(18);
    }
}
