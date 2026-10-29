package com.team3.backend.recommendation.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.repository.ApplicationStatusSpecifications;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 추천 후보 조회.
 * findTopPerGroup: 운영 추천 경로. 그룹별 우선순위 상위 후보만 DB에서 고른다.
 * findAfter: 현재 버전의 후보 전체를 ID 커서 순서로 요약 조회한다 (전체 순회 참조 구현·성능 측정용).
 */
@Repository
public class RecommendationCandidateRepository {

    /**
     * 사용자 최신 판정(ELIGIBLE·POSSIBLY_ELIGIBLE) → findAfter와 같은 후보 조건 → 숨김 제외 → 관심 적합도 → 그룹 →
     * 그룹마다 RecommendationSelectionService.priority()와 같은 순서로 상위 :limit건.
     * 최신 기준은 EligibilityResultRepository.findFreshStatuses, 관심 적합도는 MatchContext.match, 그룹은 groupOf,
     * 날짜는 RecommendationCandidateService.nearestOpenDeadline·훈련 시작일과 같다. 한쪽 규칙을 바꾸면 다른 쪽도 함께 바꾸고
     * RecommendationSelectionEquivalenceTest(전체 순회 참조 구현과 비교)로 확인한다.
     */
    private static final String TOP_PER_GROUP_SQL = """
            with fresh as (
                select r.condition_set_id, r.eligibility_status
                from eligibility_set_results r
                where r.user_id = :userId and r.rule_version = :ruleVersion
                  and r.profile_version = (select p.profile_version from eligibility_profiles p where p.user_id = :userId)
                  and (r.valid_until is null or r.valid_until > :today)
                  and r.eligibility_status in (:recommendable)
            ), candidate as (
                select o.id, v.id as version_id, o.opportunity_type, o.title, o.category, o.sub_category, o.detail_category,
                       o.created_at, f.eligibility_status,
                       case when o.opportunity_type = :policy then (
                                select min(w.end_date) from application_windows w
                                where w.version_id = v.id and w.validation_status = :valid and w.period_type = :fixed
                                  and w.end_date is not null and w.end_date >= :today
                                  and (w.start_date is null or w.start_date <= :today))
                            else d.training_start_date end as priority_date
                from fresh f
                join opportunity_versions v on v.condition_set_id = f.condition_set_id
                join opportunities o on o.current_version_id = v.id
                left join training_course_details d on d.version_id = v.id
                where v.personalization_status in (:personalization)
                  and ((o.opportunity_type = :policy and (
                          exists (select 1 from application_windows w where w.version_id = v.id
                                  and w.validation_status = :valid and w.period_type = :fixed
                                  and (w.start_date is not null or w.end_date is not null)
                                  and (w.start_date is null or w.start_date <= :today)
                                  and (w.end_date is null or w.end_date >= :today))
                          or exists (select 1 from application_windows w where w.version_id = v.id
                                     and w.validation_status = :valid and w.period_type = :always)))
                    or (o.opportunity_type = :training and d.training_start_date >= :trainingFrom))
                  and not exists (select 1 from user_opportunity_preferences h
                                  where h.user_id = :userId and h.opportunity_id = o.id and h.is_hidden = true)
            ), matched as (
                select c.*,
                       case when :unrestricted then :levelUnrestricted
                            when c.opportunity_type = :policy and :hasSub and c.sub_category in (:subCodes) then :levelHigh
                            when c.opportunity_type = :policy and :hasCategory and c.category in (:categoryCodes) then :levelMedium
                            when c.opportunity_type = :training and :hasDetail and c.detail_category in (:detailCodes) then :levelHigh
                            when c.opportunity_type = :training and :hasSub and c.sub_category in (:subCodes) then :levelMedium
                       end as match_level,
                       case when cast(c.created_at as date) between :newFrom and :today then 0 else 1 end as new_rank
                from candidate c
            ), grouped as (
                select m.*,
                       case when m.match_level = :levelUnrestricted then 'U-' || m.eligibility_status
                            else m.eligibility_status || '-' || case when m.match_level = :levelHigh then 'HIGH' else 'MEDIUM' end
                       end as grp,
                       case when m.opportunity_type = :policy and m.priority_date between :today and :policyDeadlineTo then 0
                            when m.opportunity_type = :training and m.priority_date between :trainingFrom and :trainingStartTo then 0
                            when m.new_rank = 0 then 1 else 2 end as band
                from matched m
                where m.match_level is not null
            ), ranked as (
                select g.*, row_number() over (partition by g.grp
                           order by g.band, g.priority_date asc nulls last, g.new_rank, g.id desc) as rn
                from grouped g
            )
            select id, version_id, opportunity_type, title, category, sub_category, detail_category, created_at,
                   match_level, eligibility_status
            from ranked where rn <= :limit
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 그룹(신청 가능성 × 관심 적합도, 관심 미설정이면 신청 가능성만)마다 우선순위 상위 limit건을 돌려준다 (그룹·순서 보장 없음).
     * limit은 RecommendationSelectionService가 그룹마다 유지하는 건수와 같아야 한다.
     */
    public List<TopCandidateRow> findTopPerGroup(long userId, LocalDate today, boolean unrestricted,
                                                 Set<String> subCodes, Set<String> categoryCodes, Set<String> detailCodes,
                                                 int limit) {
        List<?> rows = entityManager.createNativeQuery(TOP_PER_GROUP_SQL)
                .setParameter("userId", userId).setParameter("today", today)
                .setParameter("ruleVersion", EligibilityResultService.RULE_VERSION)
                .setParameter("recommendable", List.of(EligibilityStatus.ELIGIBLE.name(), EligibilityStatus.POSSIBLY_ELIGIBLE.name()))
                .setParameter("personalization", List.of(PersonalizationStatus.PERSONALIZABLE.name(), PersonalizationStatus.UNKNOWN.name()))
                .setParameter("policy", OpportunityType.YOUTH_POLICY.name())
                .setParameter("training", OpportunityType.VOCATIONAL_TRAINING.name())
                .setParameter("valid", ValidationStatus.VALID.name())
                .setParameter("fixed", PeriodType.FIXED.name()).setParameter("always", PeriodType.ALWAYS.name())
                .setParameter("unrestricted", unrestricted)
                .setParameter("levelUnrestricted", InterestMatchLevel.UNRESTRICTED.name())
                .setParameter("levelHigh", InterestMatchLevel.HIGH.name())
                .setParameter("levelMedium", InterestMatchLevel.MEDIUM.name())
                // 빈 목록은 IN ()이 되어 문법 오류라, 자리 표시 값을 넣고 has* 조건으로 끈다
                .setParameter("hasSub", !subCodes.isEmpty()).setParameter("subCodes", codes(subCodes))
                .setParameter("hasCategory", !categoryCodes.isEmpty()).setParameter("categoryCodes", codes(categoryCodes))
                .setParameter("hasDetail", !detailCodes.isEmpty()).setParameter("detailCodes", codes(detailCodes))
                // RecommendationSelectionService.priorityBand·isNew, findAfter의 훈련 7일 기준과 같은 날짜 경계
                .setParameter("newFrom", today.minusDays(7))
                .setParameter("policyDeadlineTo", today.plusDays(14))
                .setParameter("trainingFrom", today.plusDays(7))
                .setParameter("trainingStartTo", today.plusDays(21))
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(Object[].class::cast).map(row -> new TopCandidateRow(
                new CandidateRow(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                        OpportunityType.valueOf((String) row[2]), (String) row[3], (String) row[4], (String) row[5],
                        (String) row[6], toLocalDateTime(row[7])),
                InterestMatchLevel.valueOf((String) row[8]), EligibilityStatus.valueOf((String) row[9]))).toList();
    }

    private static List<String> codes(Set<String> codes) {
        return codes.isEmpty() ? List.of("") : List.copyOf(codes);
    }

    /** 드라이버에 따라 timestamp가 LocalDateTime 또는 java.sql.Timestamp로 온다 */
    private static LocalDateTime toLocalDateTime(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

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

    /** 그룹별 상위 후보 한 건과 DB에서 판정한 관심 적합도·최신 신청 가능성 */
    public record TopCandidateRow(CandidateRow row, InterestMatchLevel level, EligibilityStatus status) {
    }
}
