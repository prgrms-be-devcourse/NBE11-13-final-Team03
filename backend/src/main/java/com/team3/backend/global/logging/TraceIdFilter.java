package com.team3.backend.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private static final Pattern VALID_TRACE_ID =
            Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String traceId = resolveTraceId(request);
        long startTime = System.nanoTime();
        boolean unhandledException = false;

        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            unhandledException = true;
            throw exception;
        } catch (Error error) {
            unhandledException = true;
            throw error;
        } finally {
            long durationMs =
                    (System.nanoTime() - startTime) / 1_000_000;

            try {
                writeRequestLog(
                        request,
                        response,
                        durationMs,
                        unhandledException
                );
            } finally {
                MDC.remove(TRACE_ID_MDC_KEY);
            }
        }
    }

    private void writeRequestLog(
            HttpServletRequest request,
            HttpServletResponse response,
            long durationMs,
            boolean unhandledException
    ) {
        String uri = request.getRequestURI();

        if (PROMETHEUS_PATH.equals(uri)) {
            return;
        }

        int responseStatus = response.getStatus();

        int status = unhandledException && responseStatus < 500
                ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                : responseStatus;

        String method = request.getMethod();

        if (status >= 500) {
            log.error(
                    "action=HTTP_REQUEST result=FAILED reason=HTTP_{} "
                            + "method={} uri={} status={} durationMs={}",
                    status,
                    method,
                    uri,
                    status,
                    durationMs
            );
            return;
        }

        if (status >= 400) {
            log.warn(
                    "action=HTTP_REQUEST result=REJECTED reason=HTTP_{} "
                            + "method={} uri={} status={} durationMs={}",
                    status,
                    method,
                    uri,
                    status,
                    durationMs
            );
            return;
        }

        log.info(
                "action=HTTP_REQUEST result=SUCCESS reason=NONE "
                        + "method={} uri={} status={} durationMs={}",
                method,
                uri,
                status,
                durationMs
        );
    }

    private String resolveTraceId(HttpServletRequest request) {
        String receivedTraceId = request.getHeader(TRACE_ID_HEADER);

        if (receivedTraceId != null
                && VALID_TRACE_ID.matcher(receivedTraceId).matches()) {
            return receivedTraceId;
        }

        return UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}