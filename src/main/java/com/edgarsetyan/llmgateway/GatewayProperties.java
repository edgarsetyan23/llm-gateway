package com.edgarsetyan.llmgateway;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
    public List<String> apiKeys = new ArrayList<>();
    public int requestsPerMinute = 100;
    public int tokensPerMinute = 100000;
    public int defaultMaxTokens = 1024;
    public int cacheTtlSeconds = 300;
    public int cacheMaxEntries = 1000;
    public int maxAttempts = 3;
    public int backoffMillis = 100;
    public int timeoutSeconds = 15;
    public int maxConcurrentRequests = 20;
    public int circuitFailureThreshold = 3;
    public int circuitCooldownSeconds = 30;
    public List<Provider> providers = new ArrayList<>();

    public List<String> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<String> v) { apiKeys = v; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(int v) { requestsPerMinute = v; }
    public int getTokensPerMinute() { return tokensPerMinute; }
    public void setTokensPerMinute(int v) { tokensPerMinute = v; }
    public int getDefaultMaxTokens() { return defaultMaxTokens; }
    public void setDefaultMaxTokens(int v) { defaultMaxTokens = v; }
    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int v) { cacheTtlSeconds = v; }
    public int getCacheMaxEntries() { return cacheMaxEntries; }
    public void setCacheMaxEntries(int v) { cacheMaxEntries = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { maxAttempts = v; }
    public int getBackoffMillis() { return backoffMillis; }
    public void setBackoffMillis(int v) { backoffMillis = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int v) { maxConcurrentRequests = v; }
    public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
    public void setCircuitFailureThreshold(int v) { circuitFailureThreshold = v; }
    public int getCircuitCooldownSeconds() { return circuitCooldownSeconds; }
    public void setCircuitCooldownSeconds(int v) { circuitCooldownSeconds = v; }
    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> v) { providers = v; }

    public static class Provider {
        private String name = "primary", url = "", apiKey = "", model = "";
        private BigDecimal inputPricePerMillion = BigDecimal.ZERO;
        private BigDecimal outputPricePerMillion = BigDecimal.ZERO;
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
        public void setInputPricePerMillion(BigDecimal v) { inputPricePerMillion = v; }
        public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }
        public void setOutputPricePerMillion(BigDecimal v) { outputPricePerMillion = v; }
    }
}
