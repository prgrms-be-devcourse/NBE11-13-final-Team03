package com.team3.backend.interest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserInterestsUpdateRequest(

        @NotNull
        @Size(max = 30)
        List<@Valid UserInterestItemRequest> interests
) {
}