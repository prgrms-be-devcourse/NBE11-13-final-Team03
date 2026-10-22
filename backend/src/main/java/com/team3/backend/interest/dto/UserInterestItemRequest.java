package com.team3.backend.interest.dto;

import com.team3.backend.interest.domain.entity.InterestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserInterestItemRequest(

        @NotNull
        InterestType type,

        @NotBlank
        @Size(max = 100)
        String code
) {
}