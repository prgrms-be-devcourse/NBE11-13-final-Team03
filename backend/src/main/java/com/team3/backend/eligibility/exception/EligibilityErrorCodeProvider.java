package com.team3.backend.eligibility.exception;

import com.team3.backend.global.exception.ErrorCode;
import com.team3.backend.global.exception.ErrorCodeProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EligibilityErrorCodeProvider implements ErrorCodeProvider<EligibilityErrorCode> {
    @Override
    public String getDomain() {
        return "ELIGIBILITY";
    }

    @Override
    public List<ErrorCode> getErrorCodes() {
        return List.of(EligibilityErrorCode.values());
    }
}
