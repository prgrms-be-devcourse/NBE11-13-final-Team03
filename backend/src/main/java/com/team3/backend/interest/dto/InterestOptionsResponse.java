package com.team3.backend.interest.dto;

import java.util.List;

public record InterestOptionsResponse(
        List<InterestCategoryOptionResponse> categories
) {
}