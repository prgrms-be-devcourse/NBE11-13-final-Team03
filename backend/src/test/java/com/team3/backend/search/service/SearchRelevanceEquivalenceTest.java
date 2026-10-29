package com.team3.backend.search.service;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관련도(B2)의 tier·bonus·모집 상태 우선순위를 실제 공고 복원본에서 SQL 프로토타입 값과 비교하기 위한 수동 실행 테스트.
 * 기본 테스트에서는 건너뛴다. 실험용 DB에 읽기 전용(default_transaction_read_only)으로만 접속하고 스키마를 만들지 않는다.
 *
 *   YON_SEARCH_EQUIVALENCE_DB_URL / _DB_USER / _DB_PASSWORD   OpportunitySearchCandidateEquivalenceTest와 같다
 *   YON_SEARCH_RELEVANCE_QUERIES      qid<TAB>검색어 파일
 *   YON_SEARCH_RELEVANCE_CANDIDATES   qid|id 파일 (계산할 공고, 보통 프로토타입 후보)
 *   YON_SEARCH_RELEVANCE_OUTPUT       결과 파일 (qid|id|tier|bonus|status_priority)
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional(readOnly = true)
@EnabledIfEnvironmentVariable(named = "YON_SEARCH_RELEVANCE_OUTPUT", matches = ".+")
class SearchRelevanceEquivalenceTest {

    private static final int CHUNK = 1_000;

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
    private EntityManager entityManager;

    @Autowired
    private DetailValueNames detailValueNames;

    @Test
    void writeRelevanceOfCandidates() throws Exception {
        Map<String, List<Long>> candidates = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(System.getenv("YON_SEARCH_RELEVANCE_CANDIDATES")),
                StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                String[] parts = line.trim().split("\\|");
                candidates.computeIfAbsent(parts[0], key -> new ArrayList<>()).add(Long.parseLong(parts[1]));
            }
        }
        Map<String, String> policyKeywords = detailValueNames.policyKeywords();
        Map<String, String> trainingTargets = detailValueNames.trainingTargets();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();

        List<String> lines = new ArrayList<>();
        for (String row : Files.readAllLines(Path.of(System.getenv("YON_SEARCH_RELEVANCE_QUERIES")),
                StandardCharsets.UTF_8)) {
            if (row.isBlank()) {
                continue;
            }
            String[] parts = row.split("\t", 2);
            SearchKeyword keyword = SearchKeyword.parse(parts[1]);
            List<Long> ids = candidates.getOrDefault(parts[0], List.of());
            for (int from = 0; from < ids.size(); from += CHUNK) {
                List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
                CriteriaQuery<Tuple> query = criteriaBuilder.createTupleQuery();
                Root<Opportunity> root = query.from(Opportunity.class);
                query.multiselect(root.get("id"),
                        SearchRelevance.tier(root, criteriaBuilder, keyword),
                        SearchRelevance.bonus(root, query, criteriaBuilder, keyword, policyKeywords, trainingTargets),
                        SearchRelevance.statusPriority(root, query, criteriaBuilder, today));
                query.where(root.get("id").in(chunk));
                for (Tuple tuple : entityManager.createQuery(query).getResultList()) {
                    lines.add(parts[0] + "|" + tuple.get(0) + "|" + tuple.get(1) + "|" + tuple.get(2) + "|"
                            + tuple.get(3));
                }
            }
        }
        Files.write(Path.of(System.getenv("YON_SEARCH_RELEVANCE_OUTPUT")), lines, StandardCharsets.UTF_8);
    }
}
