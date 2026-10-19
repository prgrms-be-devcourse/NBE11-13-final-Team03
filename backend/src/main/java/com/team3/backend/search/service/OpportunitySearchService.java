package com.team3.backend.search.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.search.domain.repository.OpportunitySearchRepository;
import com.team3.backend.search.dto.OpportunitySearchItemDto;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** 정규화된 공고의 검색 조건과 페이지 조회를 구성한다. */
@Service
@RequiredArgsConstructor
public class OpportunitySearchService {

    private final OpportunitySearchRepository searchRepository;
    private final RegionDirectory regionDirectory;

    /** 선택한 조건을 DB에서 결합해 최신순 공고 페이지를 조회한다. */
    @Transactional(readOnly = true)
    public OpportunitySearchResponseDto search(
            String keyword, OpportunityType type, String category, String subCategory,
            String region,
            int page, int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
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
            if (matchingRegionCodes != null) {
                predicates.add(regionMatches(root, query, criteriaBuilder, matchingRegionCodes));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };

        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id"));
        Page<OpportunitySearchItemDto> results = searchRepository
                .findAll(filters, PageRequest.of(page, size, sort))
                .map(OpportunitySearchItemDto::from);

        return new OpportunitySearchResponseDto(
                results.getContent(), results.getNumber(), results.getSize(),
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
