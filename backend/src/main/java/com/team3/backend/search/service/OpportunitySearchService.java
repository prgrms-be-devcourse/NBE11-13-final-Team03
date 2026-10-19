package com.team3.backend.search.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.search.domain.repository.OpportunitySearchRepository;
import com.team3.backend.search.dto.OpportunitySearchItemDto;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
import jakarta.persistence.criteria.Predicate;
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

@Service
@RequiredArgsConstructor
public class OpportunitySearchService {

    private final OpportunitySearchRepository searchRepository;

    @Transactional(readOnly = true)
    public OpportunitySearchResponseDto search(
            String keyword, OpportunityType type, String category, String subCategory,
            int page, int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }

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
}
