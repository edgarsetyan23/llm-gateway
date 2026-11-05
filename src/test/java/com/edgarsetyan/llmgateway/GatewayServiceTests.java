package com.edgarsetyan.llmgateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class GatewayServiceTests {
    private HttpServer server;
    private final AtomicInteger primaryCalls = new AtomicInteger(), fallbackCalls = new AtomicInteger();
    private volatile int failures, failureStatus = 503;
    private volatile String response = "{\"model\":\"test-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
    private volatile String lastRequest, lastAuthorization;
    private GatewayProperties config;
    private GatewayService gateway;
    private MutableClock clock;
    private volatile CountDownLatch providerEntered, providerRelease;

    @BeforeEach void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/primary", exchange -> {
            lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            int call = primaryCalls.incrementAndGet();
            var entered = providerEntered;
            var release = providerRelease;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test provider release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            byte[] bytes = (call <= failures ? "{}" : response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(call <= failures ? failureStatus : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/fallback", exchange -> {
            lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            fallbackCalls.incrementAndGet();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        config = new GatewayProperties();
        config.apiKeys = List.of("alice", "bob");
        config.backoffMillis = 0;
        config.providers.add(provider("primary", "/primary", "test-model"));
        clock = new MutableClock();
        rebuild();
    }

    private GatewayProperties.Provider provider(String name, String path, String model) {
        var p = new GatewayProperties.Provider();
        p.setName(name);
        p.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + path);
        p.setModel(model);
        p.setApiKey("provider-secret");
        p.setInputPricePerMillion(new BigDecimal("2"));
        p.setOutputPricePerMillion(new BigDecimal("8"));
        return p;
    }
    private void rebuild() { gateway = new GatewayService(config, new ProviderClient(config, clock), clock); }
    private Map<String, Object> request() {
        return new LinkedHashMap<>(Map.of("model", "test-model", "messages", List.of(Map.of("role", "user", "content", "Hi")),
                "temperature", 0, "max_tokens", 20));
    }
    private void expectStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(GatewayException.class, action::run).status);
    }
    @AfterEach void cleanup() { server.stop(0); }

    @Test void request101IsLimitedEvenWhenCached() {
        for (int i = 0; i < 100; i++) gateway.chat("alice", request());
        expectStatus(429, () -> gateway.chat("alice", request()));
        assertEquals(1, primaryCalls.get());
        assertFalse(gateway.chat("bob", request()).cached());
    }
    @Test void identicalRequestsAreCachedButKeysAreIsolated() {
        assertFalse(gateway.chat("alice", request()).cached());
        assertTrue(gateway.chat("alice", request()).cached());
        assertFalse(gateway.chat("bob", request()).cached());
        assertEquals(2, primaryCalls.get());
    }
    @Test void usageUsesActualTokensAndSeparateInputOutputPrices() {
        gateway.chat("alice", request());
        gateway.chat("alice", request());
        var usage = gateway.usage("alice");
        assertEquals(10L, usage.get("input_tokens"));
        assertEquals(5L, usage.get("output_tokens"));
        assertEquals(1L, usage.get("provider_calls"));
        assertEquals(0, new BigDecimal("0.000060").compareTo((BigDecimal) usage.get("estimated_cost_usd")));
        assertEquals(99985L, usage.get("remaining_tokens"));
    }
    @Test void retries503ThenSucceeds() {
        failures = 1;
        gateway.chat("alice", request());
        assertEquals(2, primaryCalls.get());
    }
    @Test void retries429ThenSucceeds() {
        failures = 1; failureStatus = 429;
        gateway.chat("alice", request());
        assertEquals(2, primaryCalls.get());
    }
    @Test void tokenReservationBlocksProviderCall() {
        config.tokensPerMinute = 10; rebuild();
        expectStatus(429, () -> gateway.chat("alice", request()));
        assertEquals(0, primaryCalls.get());
    }
    @Test void failoverMapsModelAndRecordsFallbackPrice() {
        failures = 100;
        var fallback = provider("fallback", "/fallback", "backup-model");
        fallback.setOutputPricePerMillion(new BigDecimal("10"));
        config.providers.add(fallback);
        var result = gateway.chat("alice", request());
        assertEquals("fallback", result.provider());
        assertEquals(3, primaryCalls.get());
        assertEquals(1, fallbackCalls.get());
        assertTrue(lastRequest.contains("backup-model"));
        assertEquals(0, new BigDecimal("0.000070").compareTo((BigDecimal) gateway.usage("alice").get("estimated_cost_usd")));
    }
    @Test void clientErrorsAreNotRetriedOrFailedOver() {
        failures = 100; failureStatus = 401;
        config.providers.add(provider("fallback", "/fallback", "backup-model"));
        expectStatus(502, () -> gateway.chat("alice", request()));
        assertEquals(1, primaryCalls.get());
        assertEquals(0, fallbackCalls.get());
    }
    @Test void exhaustedCallsRefundReservation() {
        failures = 100;
        expectStatus(503, () -> gateway.chat("alice", request()));
        assertEquals(100000L, gateway.usage("alice").get("remaining_tokens"));
        assertEquals(0L, gateway.usage("alice").get("provider_calls"));
    }
    @Test void cacheExpiresAndBucketsRefillWithoutSleeping() {
        gateway.chat("alice", request());
        clock.advanceSeconds(301);
        assertFalse(gateway.chat("alice", request()).cached());
        assertEquals(2, primaryCalls.get());
        for (int i = 0; i < 99; i++) gateway.chat("alice", request());
        expectStatus(429, () -> gateway.chat("alice", request()));
        clock.advanceSeconds(60);
        assertTrue(gateway.chat("alice", request()).cached());
    }
    @Test void nonzeroTemperatureBypassesCache() {
        var r = request(); r.put("temperature", .5);
        gateway.chat("alice", r); gateway.chat("alice", r);
        assertEquals(2, primaryCalls.get());
    }
    @Test void outputParametersChangeCacheIdentity() {
        gateway.chat("alice", request());
        var r = request(); r.put("max_tokens", 21);
        assertFalse(gateway.chat("alice", r).cached());
        r.put("stop", "END");
        assertFalse(gateway.chat("alice", r).cached());
        assertEquals(3, primaryCalls.get());
    }
    @Test void boundedCacheEvictsOldestEntry() {
        config.cacheMaxEntries = 1; rebuild();
        gateway.chat("alice", request());
        var other = request(); other.put("max_tokens", 21);
        gateway.chat("alice", other);
        assertFalse(gateway.chat("alice", request()).cached());
    }
    @Test void defaultsMaxTokensAndForwardsProviderCredential() {
        var r = request(); r.remove("max_tokens");
        gateway.chat("alice", r);
        assertTrue(lastRequest.contains("1024"));
        assertEquals("Bearer provider-secret", lastAuthorization);
    }
    @Test void invalidKeysCannotReachProviderOrReadUsage() {
        expectStatus(401, () -> gateway.chat("intruder", request()));
        expectStatus(401, () -> gateway.usage("intruder"));
        assertEquals(0, primaryCalls.get());
    }
    @Test void invalidRequestsCannotReachProvider() {
        for (var field : List.of("stream", "tools", "temperature", "max_tokens", "messages", "model")) {
            var r = request();
            r.put(field, switch (field) {
                case "stream" -> true;
                case "tools" -> List.of();
                case "temperature" -> 3;
                case "max_tokens" -> -1;
                case "messages" -> List.of();
                default -> "unknown-model";
            });
            expectStatus(400, () -> gateway.chat("alice", r));
        }
        assertEquals(0, primaryCalls.get());
    }
    @Test void malformedProviderUsageIsRejectedAndRefunded() {
        response = "{\"choices\":[{}],\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":5}}";
        expectStatus(502, () -> gateway.chat("alice", request()));
        assertEquals(100000L, gateway.usage("alice").get("remaining_tokens"));
    }
    @Test void usageAboveReservationCreatesDebtRatherThanFreeTokens() {
        config.tokensPerMinute = 100; rebuild();
        response = "{\"choices\":[{}],\"usage\":{\"prompt_tokens\":150,\"completion_tokens\":5}}";
        gateway.chat("alice", request());
        var r = request(); r.put("temperature", 1);
        expectStatus(429, () -> gateway.chat("alice", r));
        assertEquals(0L, gateway.usage("alice").get("remaining_tokens"));
        clock.advanceSeconds(60);
        response = "{\"choices\":[{}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        gateway.chat("alice", r);
        assertEquals(2, primaryCalls.get());
    }
    @Test void disabledCacheAlwaysCallsProvider() {
        config.cacheTtlSeconds = 0; rebuild();
        gateway.chat("alice", request()); gateway.chat("alice", request());
        assertEquals(2, primaryCalls.get());
    }
    @Test void fallbackModelIsNotAccidentallyRoutedToPrimary() {
        config.providers.add(provider("fallback", "/fallback", "backup-model"));
        var r = request(); r.put("model", "backup-model");
        expectStatus(400, () -> gateway.chat("alice", r));
        assertEquals(0, primaryCalls.get());
    }
    @Test void concurrentRequestsCannotOverspendRequestBucket() throws Exception {
        config.requestsPerMinute = 5; rebuild();
        var successes = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(10)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 20; i++) tasks.add(() -> {
                try { gateway.chat("alice", request()); successes.incrementAndGet(); }
                catch (GatewayException e) { assertEquals(429, e.status); }
                return null;
            });
            for (var future : executor.invokeAll(tasks)) future.get();
        }
        assertEquals(5, successes.get());
    }
    @Test void openCircuitSkipsFailingPrimaryAndUsesFallback() {
        config.circuitFailureThreshold = 1;
        config.cacheTtlSeconds = 0;
        config.providers.add(provider("fallback", "/fallback", "backup-model"));
        failures = 100; rebuild();
        assertEquals("fallback", gateway.chat("alice", request()).provider());
        assertEquals("fallback", gateway.chat("alice", request()).provider());
        assertEquals(3, primaryCalls.get());
        assertEquals(2, fallbackCalls.get());
    }
    @Test void circuitCooldownAllowsRecoveryAndClosesCircuit() {
        config.circuitFailureThreshold = 1; config.cacheTtlSeconds = 0;
        failures = 100; rebuild();
        expectStatus(503, () -> gateway.chat("alice", request()));
        clock.advanceSeconds(29);
        expectStatus(503, () -> gateway.chat("alice", request()));
        assertEquals(3, primaryCalls.get());
        clock.advanceSeconds(1); failures = 0;
        gateway.chat("alice", request());
        gateway.chat("alice", request());
        assertEquals(5, primaryCalls.get());
    }
    @Test void permanentClientErrorsDoNotOpenCircuit() {
        config.circuitFailureThreshold = 1;
        failures = 100; failureStatus = 400; rebuild();
        expectStatus(502, () -> gateway.chat("alice", request()));
        expectStatus(502, () -> gateway.chat("alice", request()));
        assertEquals(2, primaryCalls.get());
    }
    @Test void overloadRefundsReservationAndStillAllowsCacheHits() throws Exception {
        config.maxConcurrentRequests = 1; rebuild();
        gateway.chat("alice", request()); // Seed cache before holding the only provider slot.
        providerEntered = new CountDownLatch(1);
        providerRelease = new CountDownLatch(1);
        var uncached = request(); uncached.put("temperature", 1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inFlight = executor.submit(() -> gateway.chat("alice", uncached));
            try {
                assertTrue(providerEntered.await(5, TimeUnit.SECONDS));
                expectStatus(503, () -> gateway.chat("bob", request()));
                assertEquals(100000L, gateway.usage("bob").get("remaining_tokens"));
                assertTrue(gateway.chat("alice", request()).cached());
                assertEquals(2, primaryCalls.get());
            } finally { providerRelease.countDown(); }
            assertFalse(inFlight.get(5, TimeUnit.SECONDS).cached());
        }
        providerEntered = null; providerRelease = null;
        gateway.chat("bob", request());
        assertEquals(3, primaryCalls.get());
    }
    @Test void failedProviderCallReleasesCapacity() {
        config.maxConcurrentRequests = 1; rebuild();
        failures = 100;
        expectStatus(503, () -> gateway.chat("alice", request()));
        failures = 0;
        assertFalse(gateway.chat("alice", request()).cached());
        assertEquals(4, primaryCalls.get());
    }
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
