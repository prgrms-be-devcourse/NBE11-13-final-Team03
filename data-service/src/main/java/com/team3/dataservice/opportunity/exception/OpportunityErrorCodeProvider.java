package com.team3.dataservice.opportunity.exception;

import com.team3.dataservice.global.exception.ErrorCode;
import com.team3.dataservice.global.exception.ErrorCodeProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OpportunityErrorCodeProvider implements ErrorCodeProvider<OpportunityErrorCode> {
    @Override
    public String getDomain() {
        return "OPPORTUNITY";
    }

    @Override
    public List<ErrorCode> getErrorCodes() {
        return List.of(OpportunityErrorCode.values());
    }
}
