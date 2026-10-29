package com.team3.backend.interest.service;

import com.team3.backend.interest.domain.repository.InterestOptionRepository;
import com.team3.backend.interest.dto.InterestCategoryOptionResponse;
import com.team3.backend.interest.dto.InterestOptionRow;
import com.team3.backend.interest.dto.InterestOptionsResponse;
import com.team3.backend.interest.dto.InterestSubCategoryOptionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class InterestOptionService {

    private final InterestOptionRepository interestOptionRepository;

    /**
     * 정규화된 공고의 분류를
     * 대분류 → 중분류 → 세분류 계층으로 구성한다.
     */
    @Transactional(readOnly = true)
    public InterestOptionsResponse getOptions() {
        Map<String, Map<String, Set<String>>> hierarchy =
                new TreeMap<>();

        for (InterestOptionRow row :
                interestOptionRepository
                        .findDistinctNormalizedCategories()) {

            if (!hasText(row.category())) {
                continue;
            }

            Map<String, Set<String>> subCategories =
                    hierarchy.computeIfAbsent(
                            row.category(),
                            ignored -> new TreeMap<>()
                    );

            if (!hasText(row.subCategory())) {
                continue;
            }

            Set<String> detailCategories =
                    subCategories.computeIfAbsent(
                            row.subCategory(),
                            ignored -> new TreeSet<>()
                    );

            if (hasText(row.detailCategory())) {
                detailCategories.add(row.detailCategory());
            }
        }

        return new InterestOptionsResponse(
                hierarchy.entrySet()
                        .stream()
                        .map(categoryEntry ->
                                new InterestCategoryOptionResponse(
                                        categoryEntry.getKey(),
                                        categoryEntry.getValue()
                                                .entrySet()
                                                .stream()
                                                .map(subCategoryEntry ->
                                                        new InterestSubCategoryOptionResponse(
                                                                subCategoryEntry.getKey(),
                                                                subCategoryEntry
                                                                        .getValue()
                                                                        .stream()
                                                                        .toList()
                                                        )
                                                )
                                                .toList()
                                )
                        )
                        .toList()
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}