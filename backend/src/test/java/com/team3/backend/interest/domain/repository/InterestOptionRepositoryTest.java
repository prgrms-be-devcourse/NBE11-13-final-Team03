package com.team3.backend.interest.domain.repository;

import com.team3.backend.fixture.PolicyCategoryRows;
import org.junit.jupiter.api.Tag;

import com.team3.backend.interest.dto.InterestOptionRow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@ActiveProfiles("test")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("postgres")
class InterestOptionRepositoryTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("interest_options_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add(
                "spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver"
        );
        registry.add(
                "spring.jpa.hibernate.ddl-auto",
                () -> "create-drop"
        );
    }

    @Autowired
    private InterestOptionRepository interestOptionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 현재버전이_있는_공고의_분류만_조회한다() {
        createOpportunity("정규화 완료", "일자리", "취업지원", null, true);
        createOpportunity("정규화 전", "주거", "주거지원", null, false);

        List<InterestOptionRow> result = findCategories();

        assertEquals(
                List.of(new InterestOptionRow(OpportunityType.YOUTH_POLICY, "일자리", "취업지원", null)),
                result
        );
    }

    @Test
    void 동일한_분류조합은_한번만_조회한다() {
        createOpportunity("정책 A", "일자리", "취업지원", null, true);
        createOpportunity("정책 B", "일자리", "취업지원", null, true);

        List<InterestOptionRow> result = findCategories();

        assertEquals(
                List.of(new InterestOptionRow(OpportunityType.YOUTH_POLICY, "일자리", "취업지원", null)),
                result
        );
    }

    @Test
    void 같은_대중분류라도_세분류가_다르면_각각_조회한다() {
        createOpportunity(
                "훈련 A",
                "교육･직업훈련",
                "정보통신",
                "정보기술",
                true
        );
        createOpportunity(
                "훈련 B",
                "교육･직업훈련",
                "정보통신",
                "통신기술",
                true
        );

        List<InterestOptionRow> result = findCategories();

        assertEquals(2, result.size());
        assertTrue(result.contains(
                new InterestOptionRow(OpportunityType.VOCATIONAL_TRAINING, "교육･직업훈련", "정보통신", "정보기술")
        ));
        assertTrue(result.contains(
                new InterestOptionRow(OpportunityType.VOCATIONAL_TRAINING, "교육･직업훈련", "정보통신", "통신기술")
        ));
    }

    @Test
    void 대분류가_null인_공고는_제외한다() {
        createOpportunity("대분류 없음", null, "취업지원", null, true);

        List<InterestOptionRow> result = findCategories();

        assertTrue(result.isEmpty());
    }

    @Test
    void 청년정책은_분류행의_대중분류짝을_개별_선택지로_계층과_중복없이_만든다() {
        // (일자리, 취업), (교육, 미래역량강화) / (일자리, 취업), (일자리, 창업) / 대분류만·중분류만 있는 행
        Opportunity multi = createOpportunity("정책 A", "일자리,교육", "취업,미래역량강화", null, true);
        categoryRows(multi, new String[]{"일자리", "취업"}, new String[]{"교육", "미래역량강화"});
        Opportunity duplicate = createOpportunity("정책 B", "일자리,일자리", "취업,창업", null, true);
        categoryRows(duplicate, new String[]{"일자리", "취업"}, new String[]{"일자리", "창업"});
        Opportunity uneven = createOpportunity("정책 C", "주거,복지", "월세", null, true);
        categoryRows(uneven, new String[]{"주거", null}, new String[]{"복지", null}, new String[]{null, "월세"});
        // 분류 행이 없는 백필 전 공고는 원문 컬럼을 선택지로 쓰지 않는다
        createOpportunity("정책 D", "금융,문화", "대출,공연", null, true);
        createOpportunity("훈련 A", "교육･직업훈련", "정보통신", "정보기술", true);

        var options = new com.team3.backend.interest.service.InterestOptionService(interestOptionRepository)
                .getOptions();
        entityManager.clear();

        Map<String, Map<String, List<String>>> hierarchy = new java.util.TreeMap<>();
        options.categories().forEach(category -> {
            Map<String, List<String>> subs = new java.util.TreeMap<>();
            category.subCategories().forEach(sub -> subs.put(sub.subCategory(), sub.detailCategories()));
            hierarchy.put(category.category(), subs);
        });
        assertEquals(Map.of(
                "교육", Map.of("미래역량강화", List.of()),
                "교육･직업훈련", Map.of("정보통신", List.of("정보기술")),
                "복지", Map.of(),
                "일자리", Map.of("창업", List.of(), "취업", List.of()),
                "주거", Map.of()
        ), hierarchy);
        // 대분류는 한 번만, 정렬된 순서로 나온다
        assertEquals(List.of("교육", "교육･직업훈련", "복지", "일자리", "주거"),
                options.categories().stream().map(category -> category.category()).toList());
    }

    @Test
    void 청년정책과_직업훈련의_같은_대분류명은_공고유형으로_구분된다() {
        // 실제 데이터: 청년정책 원본 대분류 '교육･직업훈련' (옛 분류명) / 직업훈련 대분류 '교육･직업훈련'
        Opportunity policy = createOpportunity("정책", "교육･직업훈련,교육･직업훈련", "온·오프라인교육 ,미래역량강화", null, true);
        categoryRows(policy, new String[]{"교육･직업훈련", "온·오프라인교육"}, new String[]{"교육･직업훈련", "미래역량강화"});
        createOpportunity("훈련", "교육･직업훈련", "정보통신", "정보기술", true);

        var options = new com.team3.backend.interest.service.InterestOptionService(interestOptionRepository)
                .getOptions();

        var shared = options.categories().getFirst();
        assertEquals("교육･직업훈련", shared.category());
        assertEquals(List.of(OpportunityType.YOUTH_POLICY, OpportunityType.VOCATIONAL_TRAINING), shared.opportunityTypes());
        Map<String, List<OpportunityType>> subTypes = new java.util.TreeMap<>();
        shared.subCategories().forEach(sub -> subTypes.put(sub.subCategory(), sub.opportunityTypes()));
        assertEquals(Map.of(
                "미래역량강화", List.of(OpportunityType.YOUTH_POLICY),
                "온·오프라인교육", List.of(OpportunityType.YOUTH_POLICY),
                "정보통신", List.of(OpportunityType.VOCATIONAL_TRAINING)), subTypes);
    }

    @Test
    void 백필후에는_분류행으로_선택지가_생긴다() {
        createOpportunity("백필 전 정책", "일자리", "취업지원", null, true);
        entityManager.flush();
        assertTrue(interestOptionRepository.findDistinctNormalizedCategories().isEmpty());

        PolicyCategoryRows.backfill(entityManager);

        assertEquals(List.of(new InterestOptionRow(OpportunityType.YOUTH_POLICY, "일자리", "취업지원", null)), findCategories());
    }

    private void categoryRows(Opportunity opportunity, String[]... rows) {
        short order = 0;
        for (String[] row : rows) {
            entityManager.persist(com.team3.backend.opportunity.domain.entity.OpportunityCategory.builder()
                    .version(opportunity.getCurrentVersion()).sortOrder(order++)
                    .largeCategory(row[0]).mediumCategory(row[1]).build());
        }
    }

    private List<InterestOptionRow> findCategories() {
        PolicyCategoryRows.backfill(entityManager);
        entityManager.clear();

        return interestOptionRepository.findDistinctNormalizedCategories();
    }

    private Opportunity createOpportunity(
            String title,
            String category,
            String subCategory,
            String detailCategory,
            boolean normalized
    ) {
        Opportunity opportunity = Opportunity.builder()
                .opportunityType(
                        detailCategory == null
                                ? OpportunityType.YOUTH_POLICY
                                : OpportunityType.VOCATIONAL_TRAINING
                )
                .title(title)
                .category(category)
                .subCategory(subCategory)
                .detailCategory(detailCategory)
                .build();

        entityManager.persist(opportunity);

        if (!normalized) {
            return opportunity;
        }

        OpportunityVersion version = OpportunityVersion.builder()
                .opportunity(opportunity)
                .versionNo(1)
                .normalizedSnapshot(Map.of("title", title))
                .personalizationStatus(PersonalizationStatus.PERSONALIZABLE)
                .normalizationVersion("test")
                .build();

        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
        return opportunity;
    }
}
