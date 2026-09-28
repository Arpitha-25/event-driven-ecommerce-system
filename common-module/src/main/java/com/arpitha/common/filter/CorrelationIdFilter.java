package com.arpitha.common.filter;

import com.arpitha.common.constant.HeaderNames;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(HeaderNames.CORRELATION_ID);

        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }

        CorrelationIdContext.setCorrelationId(correlationId);
        MDC.put("correlationId", correlationId);

        try {
            response.setHeader(HeaderNames.CORRELATION_ID, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            CorrelationIdContext.clear();
            MDC.remove("correlationId");
        }
    }
}
