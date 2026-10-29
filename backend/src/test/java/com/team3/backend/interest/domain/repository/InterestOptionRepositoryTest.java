package com.team3.backend.interest.domain.repository;

import com.team3.backend.BackendApplication;
import com.team3.backend.interest.dto.InterestOptionRow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(classes = BackendApplication.class)
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
                List.of(new InterestOptionRow("일자리", "취업지원", null)),
                result
        );
    }

    @Test
    void 동일한_분류조합은_한번만_조회한다() {
        createOpportunity("정책 A", "일자리", "취업지원", null, true);
        createOpportunity("정책 B", "일자리", "취업지원", null, true);

        List<InterestOptionRow> result = findCategories();

        assertEquals(
                List.of(new InterestOptionRow("일자리", "취업지원", null)),
                result
        );
    }

    @Test
    void 세분류가_null이어도_분류행을_조회한다() {
        createOpportunity("청년정책", "일자리", "취업지원", null, true);

        List<InterestOptionRow> result = findCategories();

        assertEquals(
                List.of(new InterestOptionRow("일자리", "취업지원", null)),
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
                new InterestOptionRow("교육･직업훈련", "정보통신", "정보기술")
        ));
        assertTrue(result.contains(
                new InterestOptionRow("교육･직업훈련", "정보통신", "통신기술")
        ));
    }

    @Test
    void 대분류가_null인_공고는_제외한다() {
        createOpportunity("대분류 없음", null, "취업지원", null, true);

        List<InterestOptionRow> result = findCategories();

        assertTrue(result.isEmpty());
    }

    private List<InterestOptionRow> findCategories() {
        entityManager.flush();
        entityManager.clear();

        return interestOptionRepository.findDistinctNormalizedCategories();
    }

    private void createOpportunity(
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
            return;
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
    }
}