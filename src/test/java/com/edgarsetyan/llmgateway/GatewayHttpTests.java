package com.edgarsetyan.llmgateway;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayHttpTests {
    private static final HttpServer provider;
    private static final AtomicInteger calls = new AtomicInteger();
    private static volatile String upstreamRequestId;
    static {
        try {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/chat", exchange -> {
                exchange.getRequestBody().readAllBytes();
                calls.incrementAndGet();
                upstreamRequestId = exchange.getRequestHeaders().getFirst("X-Request-ID");
                byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            provider.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("gateway.api-keys", () -> "http-test");
        r.add("gateway.providers[0].url", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/chat");
        r.add("gateway.providers[0].model", () -> "test-model");
        r.add("gateway.providers[0].input-price-per-million", () -> "2");
        r.add("gateway.providers[0].output-price-per-million", () -> "8");
    }
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private static final String BODY = "{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"temperature\":0,\"max_tokens\":20}";
    @AfterAll static void stop() { provider.stop(0); }

    private HttpResponse<String> send(String method, String path, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (key != null) request.header("X-API-Key", key);
        return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void httpRoundTripIncludesCacheHeadersAndUsage() throws Exception {
        var first = send("POST", "/v1/chat/completions", "http-test", BODY);
        assertEquals(200, first.statusCode());
        String firstId = first.headers().firstValue("X-Request-ID").orElseThrow();
        assertNotNull(java.util.UUID.fromString(firstId));
        assertEquals(firstId, upstreamRequestId);
        assertEquals("MISS", first.headers().firstValue("X-Gateway-Cache").orElseThrow());
        assertTrue(first.body().contains("Hello"));
        var second = send("POST", "/v1/chat/completions", "http-test", BODY);
        assertEquals("HIT", second.headers().firstValue("X-Gateway-Cache").orElseThrow());
        assertNotEquals(firstId, second.headers().firstValue("X-Request-ID").orElseThrow());
        assertEquals(1, calls.get());
        var usage = send("GET", "/v1/usage", "http-test", "");
        assertEquals(200, usage.statusCode());
        assertTrue(usage.body().contains("0.00006"));
    }
    @Test void missingKeyAndMalformedJsonHaveUsefulStatusCodes() throws Exception {
        var unauthorized = send("POST", "/v1/chat/completions", null, BODY);
        assertEquals(401, unauthorized.statusCode());
        assertNotNull(java.util.UUID.fromString(unauthorized.headers().firstValue("X-Request-ID").orElseThrow()));
        assertEquals(400, send("POST", "/v1/chat/completions", "http-test", "{").statusCode());
        assertEquals(401, send("GET", "/v1/usage", "invalid", "").statusCode());
        assertEquals(200, send("GET", "/actuator/health", null, "").statusCode());
    }
}
