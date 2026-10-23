package com.team3.dataservice.opportunity.exception;

import com.team3.dataservice.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OpportunityErrorCode implements ErrorCode {

    OPPORTUNITY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "OPPORTUNITY_001",
            "존재하지 않는 기회입니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;
}
