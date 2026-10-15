package com.team3.backend.auth.exception;

import com.team3.backend.global.exception.ErrorCode;
import com.team3.backend.global.exception.ErrorCodeProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AuthErrorCodeProvider implements ErrorCodeProvider<AuthErrorCode> {
    @Override
    public String getDomain() {
        return "AUTH";
    }

    @Override
    public List<ErrorCode> getErrorCodes() {
        return List.of(AuthErrorCode.values());
    }
}
