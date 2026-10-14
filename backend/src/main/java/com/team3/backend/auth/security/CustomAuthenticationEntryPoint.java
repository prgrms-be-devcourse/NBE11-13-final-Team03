package com.team3.backend.auth.security;

import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint
        implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {

        AuthErrorCode errorCode =
                (AuthErrorCode) request.getAttribute(
                        "auth_error"
                );

        if (errorCode == null) {
            errorCode =
                    AuthErrorCode.ACCESS_TOKEN_NOT_FOUND;
        }

        response.setStatus(
                errorCode.getStatus().value()
        );

        response.setContentType(
                MediaType.APPLICATION_JSON_VALUE
        );

        response.setCharacterEncoding("UTF-8");

        ErrorResponse errorResponse =
                ErrorResponse.from(errorCode);

        objectMapper.writeValue(
                response.getWriter(),
                errorResponse
        );
    }
}