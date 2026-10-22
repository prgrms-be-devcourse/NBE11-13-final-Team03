package com.team3.backend.interest.dto;

import java.util.List;

public record InterestCategoryOptionResponse(
        String category,
        List<InterestSubCategoryOptionResponse> subCategories
) {
}