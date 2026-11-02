package com.team3.backend.interest.service;

import com.team3.backend.interest.domain.repository.InterestOptionRepository;
import com.team3.backend.interest.dto.InterestCategoryOptionResponse;
import com.team3.backend.interest.dto.InterestOptionRow;
import com.team3.backend.interest.dto.InterestOptionsResponse;
import com.team3.backend.interest.dto.InterestSubCategoryOptionResponse;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
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
     * 같은 이름의 대분류는 한 노드로 합치고(기존 응답 구조 유지), 각 대분류·중분류가 나오는 공고 유형을 함께 담는다.
     * 청년정책 대분류 '교육･직업훈련'처럼 직업훈련과 이름이 같은 분류를 유형으로 구분하기 위해서다.
     */
    @Transactional(readOnly = true)
    public InterestOptionsResponse getOptions() {
        Map<String, CategoryNode> hierarchy = new TreeMap<>();

        for (InterestOptionRow row :
                interestOptionRepository
                        .findDistinctNormalizedCategories()) {

            if (!hasText(row.category()) || row.type() == null) {
                continue;
            }

            CategoryNode category = hierarchy.computeIfAbsent(row.category(), ignored -> new CategoryNode());
            category.types.add(row.type());

            if (!hasText(row.subCategory())) {
                continue;
            }

            SubCategoryNode subCategory =
                    category.subCategories.computeIfAbsent(row.subCategory(), ignored -> new SubCategoryNode());
            subCategory.types.add(row.type());

            if (hasText(row.detailCategory())) {
                subCategory.detailCategories.add(row.detailCategory());
            }
        }

        return new InterestOptionsResponse(
                hierarchy.entrySet()
                        .stream()
                        .map(categoryEntry ->
                                new InterestCategoryOptionResponse(
                                        categoryEntry.getKey(),
                                        categoryEntry.getValue()
                                                .subCategories
                                                .entrySet()
                                                .stream()
                                                .map(subCategoryEntry ->
                                                        new InterestSubCategoryOptionResponse(
                                                                subCategoryEntry.getKey(),
                                                                List.copyOf(subCategoryEntry.getValue().detailCategories),
                                                                List.copyOf(subCategoryEntry.getValue().types)
                                                        )
                                                )
                                                .toList(),
                                        List.copyOf(categoryEntry.getValue().types)
                                )
                        )
                        .toList()
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** EnumSet은 선언 순서(YOUTH_POLICY, VOCATIONAL_TRAINING)로 순회한다 */
    private static final class CategoryNode {
        private final Set<OpportunityType> types = EnumSet.noneOf(OpportunityType.class);
        private final Map<String, SubCategoryNode> subCategories = new TreeMap<>();
    }

    private static final class SubCategoryNode {
        private final Set<OpportunityType> types = EnumSet.noneOf(OpportunityType.class);
        private final Set<String> detailCategories = new TreeSet<>();
    }
}
