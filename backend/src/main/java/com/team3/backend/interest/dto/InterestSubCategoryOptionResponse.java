package com.team3.backend.interest.dto;

import java.util.List;

public record InterestSubCategoryOptionResponse(
        String subCategory,
        List<String> detailCategories
) {
}