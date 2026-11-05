package com.edgarsetyan.llmgateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class RequestTraceFilterTests {
    @Test void generatesFreshIdAndRestoresContextEvenOnException() throws Exception {
        var filter = new RequestTraceFilter();
        var request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.addHeader("X-Request-ID", "untrusted-client-id");
        var response = new MockHttpServletResponse();
        MDC.put(RequestTraceFilter.MDC_KEY, "previous-context");
        try {
            assertThrows(ServletException.class, () -> filter.doFilter(request, response, (req, res) -> {
                String id = response.getHeader("X-Request-ID");
                assertNotNull(UUID.fromString(id));
                assertEquals(id, MDC.get(RequestTraceFilter.MDC_KEY));
                throw new ServletException("simulated failure");
            }));
            assertEquals("previous-context", MDC.get(RequestTraceFilter.MDC_KEY));
        } finally { MDC.remove(RequestTraceFilter.MDC_KEY); }
    }
    @Test void logsSafeMetadataAndCleansThreadContext() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(RequestTraceFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("POST", "/secret-path");
            request.setQueryString("key=secret-query");
            request.addHeader("X-API-Key", "secret-key");
            request.setContent("secret-prompt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            new RequestTraceFilter().doFilter(request, response, (req, res) -> response.setStatus(401));
            assertNull(MDC.get(RequestTraceFilter.MDC_KEY));
            assertEquals(1, appender.list.size());
            String line = appender.list.getFirst().getFormattedMessage();
            assertTrue(line.contains("request_id=" + response.getHeader("X-Request-ID")));
            assertTrue(line.contains("route=other status=401 duration_ms="));
            assertFalse(line.contains("secret"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
