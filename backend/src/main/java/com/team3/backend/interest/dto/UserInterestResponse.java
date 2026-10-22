package com.team3.backend.interest.dto;

import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.domain.entity.UserInterest;

public record UserInterestResponse(
        InterestType type,
        String code
) {

    public static UserInterestResponse from(UserInterest interest) {
        return new UserInterestResponse(
                interest.getInterestType(),
                interest.getInterestCode()
        );
    }
}