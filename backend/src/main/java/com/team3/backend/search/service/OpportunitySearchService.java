package com.team3.backend.search.service;

import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.api.EligibilitySpecifications;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.repository.ApplicationStatusSpecifications;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.search.domain.repository.OpportunitySearchRepository;
import com.team3.backend.search.dto.OpportunitySearchItemDto;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
import com.team3.backend.search.dto.OpportunitySearchSort;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 정규화된 공고를 검색하고 현재 페이지의 신청 가능성·저장 상태를 표시한다. */
@Service
@RequiredArgsConstructor
public class OpportunitySearchService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OpportunitySearchRepository searchRepository;
    private final RegionDirectory regionDirectory;
    private final EligibilityProvider eligibilityProvider;
    private final PreferenceService preferenceService;

    /** 공고를 DB에서 페이지 조회한 뒤 인증 사용자에게 현재 페이지의 개인화 상태를 덧붙인다. */
    @Transactional(readOnly = true)
    public OpportunitySearchResponseDto search(
            String keyword, OpportunityType type, String category, String subCategory, String detailCategory,
            String region, ApplicationStatus applicationStatus,
            Long userId, List<EligibilityStatus> eligibilityStatuses,
            LocalDate deadlineFrom, LocalDate deadlineTo,
            LocalDate startDateFrom, LocalDate startDateTo,
            OpportunitySearchSort sort,
            int page, int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }
        boolean hasDeadlineRange = deadlineFrom != null || deadlineTo != null;
        boolean hasStartRange = startDateFrom != null || startDateTo != null;
        if ((deadlineFrom != null && deadlineTo != null && deadlineFrom.isAfter(deadlineTo))
                || (startDateFrom != null && startDateTo != null && startDateFrom.isAfter(startDateTo))
                || (hasDeadlineRange && type != OpportunityType.YOUTH_POLICY)
                || (hasStartRange && type != OpportunityType.VOCATIONAL_TRAINING)
                || (sort == OpportunitySearchSort.DEADLINE_PRIORITY && type != OpportunityType.YOUTH_POLICY)
                || (sort == OpportunitySearchSort.START_DATE_ASC && type != OpportunityType.VOCATIONAL_TRAINING)) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }

        Set<String> regionCodes = null;
        if (region != null) {
            RegionIndex index = regionDirectory.index();
            if (!index.contains(region)) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
            }
            // 상위로 지정된 정책과 하위 지역의 공고를 모두 선택 지역에 매칭한다.
            regionCodes = new TreeSet<>(index.ancestorsAndSelf(region));
            addDescendants(index, region, regionCodes);
        }
        Set<String> matchingRegionCodes = regionCodes;

        Specification<Opportunity> filters = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.isNotNull(root.get("currentVersion")));
            if (StringUtils.hasText(keyword)) {
                String escaped = keyword.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("title")), "%" + escaped + "%", '\\'));
            }
            if (type != null) {
                predicates.add(criteriaBuilder.equal(root.get("opportunityType"), type));
            }
            if (StringUtils.hasText(category)) {
                predicates.add(criteriaBuilder.equal(root.get("category"), category.trim()));
            }
            if (StringUtils.hasText(subCategory)) {
                predicates.add(criteriaBuilder.equal(root.get("subCategory"), subCategory.trim()));
            }
            if (StringUtils.hasText(detailCategory)) {
                predicates.add(criteriaBuilder.equal(root.get("detailCategory"), detailCategory.trim()));
            }
            if (matchingRegionCodes != null) {
                predicates.add(regionMatches(root, query, criteriaBuilder, matchingRegionCodes));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
        LocalDate today = LocalDate.now(KST);
        if (applicationStatus != null) {
            if (applicationStatus == ApplicationStatus.OPEN) {
                // 개강 예정 훈련은 모집 상태를 바꾸지 않고 OPEN 검색에만 포함한다.
                filters = filters.and((root, query, criteriaBuilder) -> criteriaBuilder.or(
                        criteriaBuilder.and(
                                criteriaBuilder.equal(root.get("opportunityType"), OpportunityType.YOUTH_POLICY),
                                ApplicationStatusSpecifications.hasStatus(ApplicationStatus.OPEN, today)
                                        .toPredicate(root, query, criteriaBuilder)),
                        criteriaBuilder.and(
                                criteriaBuilder.equal(root.get("opportunityType"), OpportunityType.VOCATIONAL_TRAINING),
                                startDateMatches(root, query, criteriaBuilder, today, null))
                ));
            } else {
                filters = filters.and(ApplicationStatusSpecifications.hasStatus(applicationStatus, today));
            }
        }
        if (eligibilityStatuses != null && !eligibilityStatuses.isEmpty()) {
            // 저장된 최신 판정만 DB에서 결합해 페이지와 전체 건수에 동일하게 반영한다.
            filters = filters.and(EligibilitySpecifications.statusIn(userId, eligibilityStatuses, today));
        }
        // 정책은 유효한 신청 마감일, 훈련은 교육 시작일을 사용해 서로 다른 날짜를 혼합하지 않는다.
        if (hasDeadlineRange) {
            filters = filters.and((root, query, criteriaBuilder) ->
                    deadlineMatches(root, query, criteriaBuilder, deadlineFrom, deadlineTo));
        }
        if (hasStartRange) {
            filters = filters.and((root, query, criteriaBuilder) ->
                    startDateMatches(root, query, criteriaBuilder, startDateFrom, startDateTo));
        }

        Sort pageSort = switch (sort) {
            case LATEST -> Sort.by(Sort.Direction.DESC, "createdAt", "id");
            case OLDEST -> Sort.by(Sort.Direction.ASC, "createdAt", "id");
            case DEADLINE_PRIORITY, START_DATE_ASC -> Sort.unsorted();
        };
        if (sort == OpportunitySearchSort.DEADLINE_PRIORITY) {
            filters = filters.and((root, query, criteriaBuilder) -> {
                if (!Long.class.equals(query.getResultType())) {
                    orderByDeadlinePriority(root, query, criteriaBuilder, today);
                }
                return criteriaBuilder.conjunction();
            });
        } else if (sort == OpportunitySearchSort.START_DATE_ASC) {
            filters = filters.and((root, query, criteriaBuilder) -> {
                if (!Long.class.equals(query.getResultType())) {
                    orderByTrainingStart(root, query, criteriaBuilder);
                }
                return criteriaBuilder.conjunction();
            });
        }
        // DB에서 대표 날짜를 정렬한 뒤 페이지를 잘라야 결과 순서와 전체 건수가 유지된다.
        Page<Opportunity> results = searchRepository.findAll(
                filters, PageRequest.of(page, size, pageSort));
        Map<Long, EligibilityStatus> freshStatuses = Map.of();
        Set<Long> savedIds = Set.of();
        if (userId != null && !results.isEmpty()) {
            List<Long> opportunityIds = results.getContent().stream().map(Opportunity::getId).toList();
            freshStatuses = eligibilityProvider.findFreshStatuses(userId, opportunityIds, today);
            savedIds = preferenceService.getSavedOpportunityIds(userId, opportunityIds);
        }
        Map<Long, EligibilityStatus> statusesById = freshStatuses;
        Set<Long> savedOpportunityIds = savedIds;
        List<OpportunitySearchItemDto> items = results.getContent().stream()
                .map(opportunity -> OpportunitySearchItemDto.from(
                        opportunity, statusesById.get(opportunity.getId()),
                        savedOpportunityIds.contains(opportunity.getId())))
                .toList();

        return new OpportunitySearchResponseDto(
                items, results.getNumber(), results.getSize(),
                results.getTotalElements(), results.getTotalPages(), results.hasNext()
        );
    }

    private static void addDescendants(RegionIndex index, String code, Set<String> codes) {
        for (String child : index.children(code)) {
            if (codes.add(child)) {
                addDescendants(index, child, codes);
            }
        }
    }

    private static Predicate deadlineMatches(
            Root<Opportunity> root, CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder, LocalDate from, LocalDate to
    ) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<ApplicationWindow> window = subquery.from(ApplicationWindow.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(criteriaBuilder.equal(window.get("version"), root.get("currentVersion")));
        predicates.add(criteriaBuilder.equal(window.get("validationStatus"), ValidationStatus.VALID));
        predicates.add(criteriaBuilder.equal(window.get("periodType"), PeriodType.FIXED));
        predicates.add(criteriaBuilder.isNotNull(window.get("endDate")));
        if (from != null) {
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(window.get("endDate"), from));
        }
        if (to != null) {
            predicates.add(criteriaBuilder.lessThanOrEqualTo(window.get("endDate"), to));
        }
        subquery.select(window.get("id")).where(predicates.toArray(Predicate[]::new));
        return criteriaBuilder.exists(subquery);
    }

    private static Predicate startDateMatches(
            Root<Opportunity> root, CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder, LocalDate from, LocalDate to
    ) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<TrainingCourseDetail> detail = subquery.from(TrainingCourseDetail.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(criteriaBuilder.equal(detail.get("version"), root.get("currentVersion")));
        predicates.add(criteriaBuilder.isNotNull(detail.get("trainingStartDate")));
        if (from != null) {
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(detail.get("trainingStartDate"), from));
        }
        if (to != null) {
            predicates.add(criteriaBuilder.lessThanOrEqualTo(detail.get("trainingStartDate"), to));
        }
        subquery.select(detail.get("id")).where(predicates.toArray(Predicate[]::new));
        return criteriaBuilder.exists(subquery);
    }

    private static void orderByDeadlinePriority(
            Root<Opportunity> root, CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder, LocalDate today
    ) {
        Predicate open = ApplicationStatusSpecifications.hasStatus(ApplicationStatus.OPEN, today)
                .toPredicate(root, query, criteriaBuilder);
        Predicate upcoming = ApplicationStatusSpecifications.hasStatus(ApplicationStatus.UPCOMING, today)
                .toPredicate(root, query, criteriaBuilder);
        Predicate alwaysOpen = ApplicationStatusSpecifications.hasStatus(ApplicationStatus.ALWAYS_OPEN, today)
                .toPredicate(root, query, criteriaBuilder);
        Predicate closed = ApplicationStatusSpecifications.hasStatus(ApplicationStatus.CLOSED, today)
                .toPredicate(root, query, criteriaBuilder);

        // 공고 상태는 공통 판정으로 정하고, 대표 마감일만 그 상태에 해당하는 FIXED 기간에서 고른다.
        Subquery<LocalDate> openDeadline = deadlineForStatus(root, query, criteriaBuilder, today, ApplicationStatus.OPEN);
        Subquery<LocalDate> upcomingDeadline = deadlineForStatus(root, query, criteriaBuilder, today, ApplicationStatus.UPCOMING);
        Subquery<LocalDate> closedDeadline = deadlineForStatus(root, query, criteriaBuilder, today, ApplicationStatus.CLOSED);
        Expression<Integer> priority = criteriaBuilder.<Integer>selectCase()
                .when(open, 0).when(upcoming, 1).when(alwaysOpen, 2).when(closed, 3).otherwise(4);
        Expression<LocalDate> date = criteriaBuilder.<LocalDate>selectCase()
                .when(open, openDeadline).when(upcoming, upcomingDeadline)
                .when(closed, closedDeadline).otherwise(criteriaBuilder.nullLiteral(LocalDate.class));
        Expression<LocalDate> ascendingDate = criteriaBuilder.<LocalDate>selectCase()
                .when(closed, criteriaBuilder.nullLiteral(LocalDate.class)).otherwise(date);
        Expression<LocalDate> descendingDate = criteriaBuilder.<LocalDate>selectCase()
                .when(closed, date).otherwise(criteriaBuilder.nullLiteral(LocalDate.class));
        query.orderBy(
                criteriaBuilder.asc(priority),
                criteriaBuilder.asc(criteriaBuilder.<Integer>selectCase()
                        .when(criteriaBuilder.isNull(date), 1).otherwise(0)),
                criteriaBuilder.asc(ascendingDate),
                criteriaBuilder.desc(descendingDate),
                criteriaBuilder.desc(root.get("id"))
        );
    }

    private static Subquery<LocalDate> deadlineForStatus(
            Root<Opportunity> root, CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder, LocalDate today, ApplicationStatus status
    ) {
        Subquery<LocalDate> deadline = query.subquery(LocalDate.class);
        Root<ApplicationWindow> window = deadline.from(ApplicationWindow.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(criteriaBuilder.equal(window.get("version"), root.get("currentVersion")));
        predicates.add(criteriaBuilder.equal(window.get("validationStatus"), ValidationStatus.VALID));
        predicates.add(criteriaBuilder.equal(window.get("periodType"), PeriodType.FIXED));
        switch (status) {
            case OPEN -> {
                predicates.add(criteriaBuilder.or(criteriaBuilder.isNull(window.get("startDate")),
                        criteriaBuilder.lessThanOrEqualTo(window.get("startDate"), today)));
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(window.get("endDate"), today));
            }
            case UPCOMING -> {
                predicates.add(criteriaBuilder.greaterThan(window.get("startDate"), today));
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(window.get("endDate"), today));
            }
            case CLOSED -> {
                predicates.add(criteriaBuilder.or(criteriaBuilder.isNull(window.get("startDate")),
                        criteriaBuilder.lessThanOrEqualTo(window.get("startDate"), today)));
                predicates.add(criteriaBuilder.lessThan(window.get("endDate"), today));
            }
            default -> throw new IllegalArgumentException("마감일 정렬 대상이 아닌 신청 상태다.");
        }
        Expression<LocalDate> endDate = window.get("endDate");
        deadline.select(status == ApplicationStatus.CLOSED
                ? criteriaBuilder.greatest(endDate) : criteriaBuilder.least(endDate));
        deadline.where(predicates.toArray(Predicate[]::new));
        return deadline;
    }

    private static void orderByTrainingStart(
            Root<Opportunity> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder
    ) {
        Subquery<LocalDate> startDate = query.subquery(LocalDate.class);
        Root<TrainingCourseDetail> detail = startDate.from(TrainingCourseDetail.class);
        startDate.select(detail.get("trainingStartDate")).where(
                criteriaBuilder.equal(detail.get("version"), root.get("currentVersion"))
        );
        orderByNullableDate(root, query, criteriaBuilder, startDate);
    }

    private static void orderByNullableDate(
            Root<Opportunity> root, CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder, Subquery<LocalDate> date
    ) {
        // 날짜 미확인 공고는 유지하되 뒤로 보내고, 동률은 ID로 고정한다.
        query.orderBy(
                criteriaBuilder.asc(criteriaBuilder.selectCase()
                        .when(criteriaBuilder.isNull(date), 1).otherwise(0)),
                criteriaBuilder.asc(date),
                criteriaBuilder.desc(root.get("id"))
        );
    }

    private static Predicate regionMatches(
            Root<Opportunity> root, CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder, Set<String> regionCodes
    ) {
        Predicate youthPolicy = criteriaBuilder.equal(root.get("opportunityType"), OpportunityType.YOUTH_POLICY);
        Predicate training = criteriaBuilder.equal(root.get("opportunityType"), OpportunityType.VOCATIONAL_TRAINING);
        // 정책의 거주지 조건과 훈련의 실제 시행 지역은 서로 다른 역할로 조회한다.
        Subquery<Long> residence = regionExists(root, query, criteriaBuilder, regionCodes, RegionRole.RESIDENCE);
        Subquery<Long> implementation = regionExists(root, query, criteriaBuilder, regionCodes, RegionRole.IMPLEMENTATION);

        Subquery<Long> national = query.subquery(Long.class);
        Root<OpportunityCondition> condition = national.from(OpportunityCondition.class);
        // 전국 거주 가능 여부는 현재 버전의 REGION 조건 JSON에만 기록된다.
        Expression<String> status = criteriaBuilder.function(
                "jsonb_extract_path_text", String.class,
                condition.get("expression"), criteriaBuilder.literal("status"));
        national.select(condition.get("id")).where(
                criteriaBuilder.equal(condition.get("version"), root.get("currentVersion")),
                criteriaBuilder.equal(condition.get("conditionType"), ConditionType.REGION),
                criteriaBuilder.equal(condition.get("conditionField"), "residenceRegion"),
                criteriaBuilder.equal(status, "NATIONAL")
        );

        // EXISTS로 복수 지역 행의 중복을 막고, NATIONAL은 정책에만 적용한다.
        return criteriaBuilder.or(
                criteriaBuilder.and(youthPolicy, criteriaBuilder.or(
                        criteriaBuilder.exists(residence), criteriaBuilder.exists(national))),
                criteriaBuilder.and(training, criteriaBuilder.exists(implementation))
        );
    }

    private static Subquery<Long> regionExists(
            Root<Opportunity> root, CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder, Set<String> regionCodes, RegionRole role
    ) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<OpportunityRegion> region = subquery.from(OpportunityRegion.class);
        subquery.select(region.get("id")).where(
                criteriaBuilder.equal(region.get("version"), root.get("currentVersion")),
                criteriaBuilder.equal(region.get("regionRole"), role),
                region.get("region").get("regionCode").in(regionCodes)
        );
        return subquery;
    }
}
