package com.flyo.rescue.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Enterprise Distributed Tracing & Correlation Filter.
 *
 * Populates SLF4J MDC with correlation and trace identifiers:
 * - X-Correlation-ID (propagated or generated)
 * - X-Trace-ID (OpenTelemetry / distributed tracing compatible)
 *
 * Echoes trace headers in HTTP responses for downstream and client-side observability.
 * Ensures MDC is safely cleared on request completion.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationMdcFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String TRACE_ID_HEADER = "X-Trace-ID";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_TENANT_ID = "tenantId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = request.getHeader(REQUEST_ID_HEADER);
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "corr-" + UUID.randomUUID().toString().substring(0, 8);
        }

        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = "tr-" + UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_TRACE_ID, traceId);

        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_TENANT_ID);
        }
    }
}
