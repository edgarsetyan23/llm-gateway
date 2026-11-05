package com.edgarsetyan.llmgateway;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.slf4j.MDC;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class ProviderClient {
    private final GatewayProperties config;
    private final RestClient client;
    private final Clock clock;
    private final Semaphore capacity;
    private final Map<GatewayProperties.Provider, ProviderCircuit> circuits = new ConcurrentHashMap<>();
    public record Reply(Map<String, Object> body, GatewayProperties.Provider provider) {}

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderClient(GatewayProperties config) {
        this(config, Clock.systemUTC());
    }

    ProviderClient(GatewayProperties config, Clock clock) {
        this.config = config;
        this.clock = clock;
        if (config.maxConcurrentRequests < 1 || config.circuitFailureThreshold < 1 || config.circuitCooldownSeconds < 1)
            throw new IllegalArgumentException("Concurrency and circuit settings must be positive");
        capacity = new Semaphore(config.maxConcurrentRequests);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds)).build());
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds));
        client = RestClient.builder().requestFactory(factory).build();
    }

    public Reply call(Map<String, Object> request) {
        if (!capacity.tryAcquire()) throw new GatewayException(503, "Gateway provider capacity is full; try again later.");
        try {
            return callWithCapacity(request);
        } finally {
            capacity.release();
        }
    }

    @SuppressWarnings("unchecked")
    private Reply callWithCapacity(Map<String, Object> request) {
        for (var provider : config.providers) {
            if (provider.getUrl().isBlank()) continue;
            var circuit = circuits.computeIfAbsent(provider, ignored -> new ProviderCircuit(
                    config.circuitFailureThreshold, config.circuitCooldownSeconds, clock));
            var permit = circuit.acquire();
            if (permit == null) continue;
            boolean reachable = false;
            try {
                var body = new LinkedHashMap<>(request);
                if (!provider.getModel().isBlank()) body.put("model", provider.getModel());
                for (int attempt = 0; attempt < config.maxAttempts; attempt++) {
                    try {
                        var outgoing = client.post().uri(provider.getUrl())
                                .header("Content-Type", "application/json");
                        if (!provider.getApiKey().isBlank()) outgoing.header("Authorization", "Bearer " + provider.getApiKey());
                        String requestId = MDC.get(RequestTraceFilter.MDC_KEY);
                        if (requestId != null) outgoing.header("X-Request-ID", requestId);
                        Map<String, Object> response = outgoing.body(body).retrieve().body(Map.class);
                        validate(response);
                        reachable = true;
                        return new Reply(response, provider);
                    } catch (RestClientResponseException e) {
                        int status = e.getStatusCode().value();
                        if (status != 429 && status < 500) {
                            reachable = true;
                            throw new GatewayException(502, "Provider rejected the request (HTTP " + status + ").");
                        }
                    } catch (ResourceAccessException e) {
                        // Connection failures and timeouts can recover on another attempt/provider.
                    } catch (RestClientException e) {
                        throw new GatewayException(502, "Provider returned an unreadable response.");
                    }
                    if (attempt + 1 < config.maxAttempts) pause(attempt);
                }
            } finally {
                circuit.complete(permit, reachable);
            }
        }
        throw new GatewayException(503, "No provider available; retries and failover exhausted.");
    }

    static void validate(Map<String, Object> response) {
        if (response == null || !(response.get("choices") instanceof java.util.List<?> choices)
                || choices.isEmpty() || !(response.get("usage") instanceof Map<?, ?> usage))
            throw new GatewayException(502, "Provider response must contain choices and usage.");
        tokens(usage.get("prompt_tokens"));
        tokens(usage.get("completion_tokens"));
    }

    static long tokens(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0
                || n.doubleValue() > Integer.MAX_VALUE || n.doubleValue() != n.longValue())
            throw new GatewayException(502, "Provider returned invalid token usage.");
        return n.longValue();
    }

    private void pause(int attempt) {
        long ceiling = Math.min(5000L, (long) config.backoffMillis * (1L << Math.min(attempt, 10)));
        try {
            Thread.sleep(ceiling == 0 ? 0 : ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(503, "Provider request interrupted.");
        }
    }
}
