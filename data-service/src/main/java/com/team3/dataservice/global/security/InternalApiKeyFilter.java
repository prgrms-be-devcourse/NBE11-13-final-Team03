package com.team3.dataservice.global.security;

import com.team3.dataservice.global.exception.ErrorResponse;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * DataService의 API는 모두 내부용이다. 메인 서비스만 X-INTERNAL-KEY 헤더(internal.api-key)로 호출한다.
 * /internal/** 밖의 경로는 컨트롤러가 없어 404가 된다. 키가 없거나 다르면 401 (메인과 같은 AUTH_008 응답).
 * 사용자 인증·관리자 권한 확인은 메인 서비스에서 끝내고 넘어온다.
 */
@Component
@RequiredArgsConstructor
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-INTERNAL-KEY";
    private static final String INTERNAL_PREFIX = "/internal/";

    private final ObjectMapper objectMapper;

    @Value("${internal.api-key}")
    private String internalApiKey;

    @PostConstruct
    void validate() {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            throw new IllegalStateException("internal.api-key must not be blank");
        }
    }

    /**
     * 경로는 요청 URI에서 컨텍스트 경로를 뺀 값으로 본다.
     * getServletPath()는 서블릿 매핑에 따라 빈 문자열일 수 있어(MockMvc 등) 검사가 통째로 빠질 수 있다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                internalApiKey.getBytes(StandardCharsets.UTF_8))) {
            InternalApiErrorCode error = InternalApiErrorCode.INVALID_INTERNAL_API_KEY;
            response.setStatus(error.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), ErrorResponse.from(error));
            return;
        }
        chain.doFilter(request, response);
    }
}
