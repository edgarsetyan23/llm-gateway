package com.edgarsetyan.llmgateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);
    static final String MDC_KEY = "gatewayRequestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, id);
        response.setHeader("X-Request-ID", id);
        long start = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            // Fixed route labels exclude arbitrary path/query data, prompts, and credentials.
            String route = Set.of("/v1/chat/completions", "/v1/usage", "/actuator/health")
                    .contains(request.getRequestURI()) ? request.getRequestURI() : "other";
            String method = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                    .contains(request.getMethod()) ? request.getMethod() : "OTHER";
            log.info("gateway_request request_id={} method={} route={} status={} duration_ms={}",
                    id, method, route, completed ? response.getStatus() : 500,
                    (System.nanoTime() - start) / 1_000_000);
            if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous);
        }
    }
}
