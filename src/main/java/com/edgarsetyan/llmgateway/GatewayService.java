package com.edgarsetyan.llmgateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GatewayService {
    private final GatewayProperties config;
    private final ProviderClient providers;
    private final Clock clock;
    private final Map<String, Account> accounts = new HashMap<>();
    // Access ordered, bounded, and guarded by this service's monitor.
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final JsonMapper json = JsonMapper.builder().build();
    public record Result(Map<String, Object> body, String provider, boolean cached) {}
    private record Entry(Result result, long expiresAt) {}

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayService(GatewayProperties config, ProviderClient providers) {
        this(config, providers, Clock.systemUTC());
    }

    GatewayService(GatewayProperties config, ProviderClient providers, Clock clock) {
        this.config = config;
        this.providers = providers;
        this.clock = clock;
        if (config.requestsPerMinute <= 0 || config.tokensPerMinute <= 0 || config.defaultMaxTokens <= 0
                || config.cacheTtlSeconds < 0 || config.cacheMaxEntries <= 0 || config.maxAttempts < 1
                || config.maxAttempts > 5 || config.backoffMillis < 0 || config.timeoutSeconds <= 0)
            throw new IllegalArgumentException("Invalid gateway limits, cache, retry, or timeout configuration");
        for (var provider : config.providers) {
            if (provider.getInputPricePerMillion().signum() < 0 || provider.getOutputPricePerMillion().signum() < 0)
                throw new IllegalArgumentException("Provider prices must be nonnegative");
            if (!provider.getUrl().isBlank()) {
                var uri = java.net.URI.create(provider.getUrl());
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || provider.getModel().isBlank()
                        || !provider.getName().matches("[a-zA-Z0-9_-]{1,64}"))
                    throw new IllegalArgumentException("Each active provider needs an HTTP(S) URL, model, and simple name");
            }
        }
    }

    private static class Account {
        double requests, tokens;
        long lastRefill, inputTokens, outputTokens, calls;
        BigDecimal cost = BigDecimal.ZERO;
        Account(GatewayProperties c, long now) { requests = c.requestsPerMinute; tokens = c.tokensPerMinute; lastRefill = now; }
    }

    private Account authenticate(String key) {
        if (key.isBlank() || !config.apiKeys.contains(key)) throw new GatewayException(401, "Invalid gateway API key.");
        return accounts.computeIfAbsent(key, ignored -> new Account(config, clock.millis()));
    }

    public Result chat(String key, Map<String, Object> raw) {
        Account account;
        synchronized (this) {
            account = authenticate(key);
            refill(account);
            if (account.requests < 1) throw new GatewayException(429, "Request limit exceeded.");
            account.requests--;
        }
        Map<String, Object> request = normalize(raw);
        String cacheKey = digest(key + "\n" + json.writeValueAsString(canonical(request)));
        long reservation = estimate(request);
        synchronized (this) {
            var entry = cache.get(cacheKey);
            if (entry != null && entry.expiresAt > clock.millis()) {
                var hit = entry.result;
                return new Result(hit.body, hit.provider, true);
            }
            if (entry != null) cache.remove(cacheKey);
            refill(account);
            if (account.tokens < reservation) throw new GatewayException(429, "Token limit exceeded.");
            account.tokens -= reservation;
        }

        ProviderClient.Reply reply;
        try {
            reply = providers.call(request);
        } catch (RuntimeException e) {
            synchronized (this) {
                refill(account);
                account.tokens = Math.min(config.tokensPerMinute, account.tokens + reservation);
            }
            throw e;
        }
        var usage = (Map<?, ?>) reply.body().get("usage");
        long input = ProviderClient.tokens(usage.get("prompt_tokens"));
        long output = ProviderClient.tokens(usage.get("completion_tokens"));
        var provider = reply.provider();
        BigDecimal cost = provider.getInputPricePerMillion().multiply(BigDecimal.valueOf(input))
                .add(provider.getOutputPricePerMillion().multiply(BigDecimal.valueOf(output))).movePointLeft(6);
        var result = new Result(reply.body(), provider.getName(), false);
        synchronized (this) {
            refill(account);
            // Negative balance is intentional when actual usage exceeds the estimate.
            account.tokens = Math.min(config.tokensPerMinute, account.tokens + reservation - input - output);
            account.inputTokens += input;
            account.outputTokens += output;
            account.cost = account.cost.add(cost);
            account.calls++;
            if (request.get("temperature") instanceof Number temperature && temperature.doubleValue() == 0
                    && config.cacheTtlSeconds > 0) {
                cache.entrySet().removeIf(e -> e.getValue().expiresAt <= clock.millis());
                cache.put(cacheKey, new Entry(result, clock.millis() + config.cacheTtlSeconds * 1000L));
                while (cache.size() > config.cacheMaxEntries) cache.remove(cache.keySet().iterator().next());
            }
        }
        return result;
    }

    public synchronized Map<String, Object> usage(String key) {
        var a = authenticate(key);
        refill(a);
        return Map.of("provider_calls", a.calls, "input_tokens", a.inputTokens, "output_tokens", a.outputTokens,
                "estimated_cost_usd", a.cost, "remaining_tokens", Math.max(0, (long) a.tokens));
    }

    private void refill(Account a) {
        long now = clock.millis();
        double minutes = Math.max(0, now - a.lastRefill) / 60000.0;
        a.requests = Math.min(config.requestsPerMinute, a.requests + minutes * config.requestsPerMinute);
        a.tokens = Math.min(config.tokensPerMinute, a.tokens + minutes * config.tokensPerMinute);
        a.lastRefill = Math.max(now, a.lastRefill);
    }

    private Map<String, Object> normalize(Map<String, Object> raw) {
        Set<String> allowed = Set.of("model", "messages", "temperature", "max_tokens", "top_p", "stop", "stream");
        if (!allowed.containsAll(raw.keySet())) throw new GatewayException(400, "Unsupported request field.");
        if (!(raw.get("model") instanceof String model) || model.isBlank() || model.length() > 200)
            throw new GatewayException(400, "model must be a nonempty string (max 200 characters).");
        boolean routed = config.providers.stream().filter(p -> !p.getUrl().isBlank()).findFirst()
                .map(p -> p.getModel().equals(model)).orElse(false);
        if (!routed) throw new GatewayException(400, "No configured route for this model.");
        if (!(raw.get("messages") instanceof List<?> messages) || messages.isEmpty() || messages.size() > 100)
            throw new GatewayException(400, "messages must contain 1 to 100 text messages.");
        long characters = 0;
        for (var item : messages) {
            if (!(item instanceof Map<?, ?> message) || !Set.of("role", "content").containsAll(message.keySet())
                    || !(message.get("role") instanceof String role) || !Set.of("system", "user", "assistant").contains(role)
                    || !(message.get("content") instanceof String content))
                throw new GatewayException(400, "Each message requires a supported role and string content.");
            characters += content.length();
        }
        if (characters > 100000) throw new GatewayException(400, "Prompt exceeds 100000 characters.");
        if (raw.containsKey("stream") && !Boolean.FALSE.equals(raw.get("stream")))
            throw new GatewayException(400, "Streaming is not supported.");
        var request = new LinkedHashMap<>(raw);
        request.remove("stream");
        Object max = request.getOrDefault("max_tokens", config.defaultMaxTokens);
        if (!(max instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > 100000)
            throw new GatewayException(400, "max_tokens must be an integer from 1 to 100000.");
        request.put("max_tokens", ((Number) max).intValue());
        range(request, "temperature", 0, 2);
        range(request, "top_p", 0, 1);
        if (request.containsKey("stop")) {
            var stop = request.get("stop");
            if (!(stop instanceof String) && !(stop instanceof List<?> list && !list.isEmpty()
                    && list.size() <= 4 && list.stream().allMatch(String.class::isInstance)))
                throw new GatewayException(400, "stop must be a string or 1 to 4 strings.");
        }
        return request;
    }

    private static void range(Map<String, Object> request, String field, double min, double max) {
        if (request.containsKey(field) && (!(request.get(field) instanceof Number n)
                || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max))
            throw new GatewayException(400, field + " is outside the supported range.");
    }

    private long estimate(Map<String, Object> request) {
        return (json.writeValueAsString(request.get("messages")).length() + 3L) / 4
                + ((Number) request.get("max_tokens")).longValue();
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((k, v) -> sorted.put(k.toString(), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(GatewayService::canonical).toList();
        return value;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
