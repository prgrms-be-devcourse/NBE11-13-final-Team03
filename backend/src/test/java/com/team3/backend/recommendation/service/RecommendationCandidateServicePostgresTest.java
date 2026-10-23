package com.team3.backend.recommendation.service;

import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL에서 추천 후보의 기본 제외 조건과 ID 커서 경계를 검증한다. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class RecommendationCandidateServicePostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("yon_recommendation_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RecommendationCandidateService candidateService;

    @Test
    void candidatesUseCurrentVersionStatusAndTrainingStartBoundaryWithoutCursorGaps() {
        LocalDate today = LocalDate.of(2026, 10, 20);
        Opportunity unnormalized = opportunity("Unnormalized", OpportunityType.YOUTH_POLICY, null);
        Opportunity open = opportunity("Open policy", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE);
        open.updateSummary(open.getTitle(), "교육", "취업", null, null);
        window(open, PeriodType.FIXED, today.minusDays(1), today.plusDays(14), ValidationStatus.VALID);
        window(open, PeriodType.FIXED, today.minusDays(2), today.plusDays(3), ValidationStatus.VALID);
        window(open, PeriodType.FIXED, today.minusDays(1), today.plusDays(1), ValidationStatus.INVALID);
        Opportunity always = opportunity("Always policy", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.UNKNOWN);
        window(always, PeriodType.ALWAYS, null, null, ValidationStatus.VALID);
        Opportunity unknownPersonalization = opportunity("Unknown personalization policy", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.UNKNOWN);
        window(unknownPersonalization, PeriodType.FIXED, today, today, ValidationStatus.VALID);
        Opportunity split = opportunity("Split policy", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.SPLIT_REQUIRED);
        window(split, PeriodType.FIXED, today, today.plusDays(1), ValidationStatus.VALID);
        Opportunity nonPersonal = opportunity("Nonpersonal policy", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.NON_PERSONAL);
        window(nonPersonal, PeriodType.FIXED, today, today.plusDays(1), ValidationStatus.VALID);
        Opportunity upcoming = opportunity("Upcoming policy", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE);
        window(upcoming, PeriodType.FIXED, today.plusDays(1), today.plusDays(9), ValidationStatus.VALID);
        Opportunity closed = opportunity("Closed policy", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE);
        window(closed, PeriodType.FIXED, today.minusDays(9), today.minusDays(1), ValidationStatus.VALID);
        Opportunity unknownStatus = opportunity("Unknown policy", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE);
        window(unknownStatus, PeriodType.FIXED, today, today.plusDays(2), ValidationStatus.NEEDS_REVIEW);
        Opportunity oldOpenCurrentClosed = opportunity("Old open current closed", OpportunityType.YOUTH_POLICY,
                PersonalizationStatus.PERSONALIZABLE);
        window(oldOpenCurrentClosed, PeriodType.FIXED, today, today.plusDays(2), ValidationStatus.VALID);
        changeVersion(oldOpenCurrentClosed, PersonalizationStatus.PERSONALIZABLE);
        window(oldOpenCurrentClosed, PeriodType.FIXED, today.minusDays(9), today.minusDays(1), ValidationStatus.VALID);

        Opportunity trainingD7 = opportunity("Training D7", OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.UNKNOWN);
        trainingD7.updateSummary(trainingD7.getTitle(), "교육･직업훈련", "정보통신", "정보기술", null);
        trainingDetail(trainingD7, today.plusDays(7));
        Opportunity trainingD21 = opportunity("Training D21", OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.PERSONALIZABLE);
        trainingDetail(trainingD21, today.plusDays(21));
        Opportunity trainingD6 = opportunity("Training D6", OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.UNKNOWN);
        trainingDetail(trainingD6, today.plusDays(6));
        Opportunity trainingToday = opportunity("Training today", OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.UNKNOWN);
        trainingDetail(trainingToday, today);
        Opportunity trainingPast = opportunity("Training past", OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.UNKNOWN);
        trainingDetail(trainingPast, today.minusDays(1));
        Opportunity trainingNoDate = opportunity("Training no date", OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.UNKNOWN);
        trainingDetail(trainingNoDate, null);
        Opportunity trainingSplit = opportunity("Training split", OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.SPLIT_REQUIRED);
        trainingDetail(trainingSplit, today.plusDays(7));
        Opportunity oldD7CurrentD6 = opportunity("Old D7 current D6", OpportunityType.VOCATIONAL_TRAINING,
                PersonalizationStatus.UNKNOWN);
        trainingDetail(oldD7CurrentD6, today.plusDays(7));
        changeVersion(oldD7CurrentD6, PersonalizationStatus.UNKNOWN);
        trainingDetail(oldD7CurrentD6, today.plusDays(6));
        entityManager.flush();
        entityManager.clear();

        List<RecommendationCandidate> candidates = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        long cursor = 0;
        for (int batchIndex = 0; batchIndex < 4; batchIndex++) {
            List<RecommendationCandidate> batch = candidateService.getCandidatesAfter(cursor, 2, today);
            batchSizes.add(batch.size());
            if (batch.isEmpty()) {
                break;
            }
            assertTrue(batch.getFirst().opportunityId() > cursor);
            candidates.addAll(batch);
            cursor = batch.getLast().opportunityId();
        }

        assertEquals(List.of(2, 2, 1, 0), batchSizes);
        assertTrue(candidateService.getCandidatesAfter(cursor, 2, today).isEmpty());
        List<Long> ids = candidates.stream().map(RecommendationCandidate::opportunityId).toList();
        assertEquals(List.of(open.getId(), always.getId(), unknownPersonalization.getId(),
                trainingD7.getId(), trainingD21.getId()), ids);
        assertEquals(ids.size(), new HashSet<>(ids).size());
        RecommendationCandidate openCandidate = candidates.getFirst();
        assertEquals(OpportunityType.YOUTH_POLICY, openCandidate.type());
        assertEquals("Open policy", openCandidate.title());
        assertEquals("교육", openCandidate.category());
        assertEquals("취업", openCandidate.subCategory());
        assertEquals(ApplicationStatus.OPEN, openCandidate.applicationStatus());
        assertEquals(today.plusDays(3), openCandidate.applicationEndDate());
        assertNull(openCandidate.trainingStartDate());
        assertEquals(entityManager.find(Opportunity.class, open.getId()).getCreatedAt(), openCandidate.createdAt());
        assertEquals(ApplicationStatus.ALWAYS_OPEN, candidates.get(1).applicationStatus());
        assertNull(candidates.get(1).applicationEndDate());
        assertEquals(ApplicationStatus.OPEN, candidates.get(2).applicationStatus());
        RecommendationCandidate trainingCandidate = candidates.get(3);
        assertEquals("교육･직업훈련", trainingCandidate.category());
        assertEquals("정보통신", trainingCandidate.subCategory());
        assertEquals("정보기술", trainingCandidate.detailCategory());
        assertEquals(ApplicationStatus.UNKNOWN, trainingCandidate.applicationStatus());
        assertNull(trainingCandidate.applicationEndDate());
        assertEquals(today.plusDays(7), trainingCandidate.trainingStartDate());
        assertEquals(entityManager.find(Opportunity.class, trainingD7.getId()).getCreatedAt(),
                trainingCandidate.createdAt());
        assertEquals(today.plusDays(21), candidates.get(4).trainingStartDate());
        assertFalse(ids.contains(unnormalized.getId()));
    }

    private Opportunity opportunity(String title, OpportunityType type, PersonalizationStatus status) {
        Opportunity opportunity = Opportunity.builder().title(title).opportunityType(type).build();
        entityManager.persist(opportunity);
        if (status != null) {
            OpportunityVersion version = OpportunityVersion.builder()
                    .opportunity(opportunity).versionNo(1)
                    .normalizedSnapshot(Map.of("title", title))
                    .personalizationStatus(status).normalizationVersion("test").build();
            entityManager.persist(version);
            opportunity.changeCurrentVersion(version);
        }
        return opportunity;
    }

    private void window(Opportunity opportunity, PeriodType type, LocalDate start, LocalDate end,
                        ValidationStatus validation) {
        entityManager.persist(ApplicationWindow.builder().version(opportunity.getCurrentVersion())
                .periodType(type).startDate(start).endDate(end).validationStatus(validation).build());
    }

    private void trainingDetail(Opportunity opportunity, LocalDate start) {
        entityManager.persist(TrainingCourseDetail.builder().version(opportunity.getCurrentVersion())
                .courseId("course-" + opportunity.getId()).courseRound(1).trainingStartDate(start).build());
    }

    private void changeVersion(Opportunity opportunity, PersonalizationStatus status) {
        OpportunityVersion version = OpportunityVersion.builder()
                .opportunity(opportunity).versionNo(2)
                .normalizedSnapshot(Map.of("title", opportunity.getTitle(), "revision", 2))
                .personalizationStatus(status).normalizationVersion("test").build();
        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
    }
}
