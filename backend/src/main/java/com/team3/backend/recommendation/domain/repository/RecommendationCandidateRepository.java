package com.team3.backend.recommendation.domain.repository;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.repository.ApplicationStatusSpecifications;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 현재 버전의 추천 후보를 ID 커서 순서로 요약 조회한다. */
@Repository
public class RecommendationCandidateRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** 기본 제외 조건을 DB에서 적용하고 엔티티 대신 후보 요약을 조회한다. */
    public List<CandidateRow> findAfter(long afterId, int size, LocalDate today) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<CandidateRow> query = builder.createQuery(CandidateRow.class);
        Root<Opportunity> opportunity = query.from(Opportunity.class);
        Join<Opportunity, ?> version = opportunity.join("currentVersion");

        Predicate policyStatus = ApplicationStatusSpecifications.statusIn(
                List.of(ApplicationStatus.OPEN, ApplicationStatus.ALWAYS_OPEN), today)
                .toPredicate(opportunity, query, builder);
        Subquery<Long> training = query.subquery(Long.class);
        Root<TrainingCourseDetail> detail = training.from(TrainingCourseDetail.class);
        // 교육 시작일까지 최소 7일을 확보하며 D-6 이하와 날짜 미확인 훈련은 제외한다.
        training.select(detail.get("id")).where(
                builder.equal(detail.get("version"), version),
                builder.greaterThanOrEqualTo(detail.get("trainingStartDate"), today.plusDays(7)));

        query.select(builder.construct(CandidateRow.class,
                opportunity.get("id"), version.get("id"), opportunity.get("opportunityType"),
                opportunity.get("title"), opportunity.get("category"), opportunity.get("subCategory"),
                opportunity.get("detailCategory"), opportunity.get("createdAt")));
        query.where(
                builder.greaterThan(opportunity.get("id"), afterId),
                // UNKNOWN은 미분류 상태이며 직업훈련의 현재 정규화 값이므로 후보에서 버리지 않는다.
                version.get("personalizationStatus").in(
                        PersonalizationStatus.PERSONALIZABLE, PersonalizationStatus.UNKNOWN),
                builder.or(
                        builder.and(builder.equal(opportunity.get("opportunityType"), OpportunityType.YOUTH_POLICY),
                                policyStatus),
                        builder.and(builder.equal(opportunity.get("opportunityType"), OpportunityType.VOCATIONAL_TRAINING),
                                builder.exists(training))));
        // ID 커서는 모든 적격 공고를 순회하며 페이지 경계에서 중복·누락을 만들지 않는다.
        query.orderBy(builder.asc(opportunity.get("id")));
        return entityManager.createQuery(query).setMaxResults(size).getResultList();
    }

    /** 현재 버전과 화면·매칭에 필요한 공고 필드만 담는다. */
    public record CandidateRow(Long opportunityId, Long versionId, OpportunityType type, String title,
                               String category, String subCategory, String detailCategory, LocalDateTime createdAt) {
    }
}
