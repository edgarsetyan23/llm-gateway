# llm-gateway

A Java Spring Boot service that sits between application code and LLM provider APIs
(OpenAI, Anthropic). Services call the gateway instead of calling providers directly.

## What it is

Calling LLM providers directly from application code causes three problems:

- **No shared resilience.** When a provider is overloaded or a quota is hit, it returns
  HTTP 429 or 503. Without a gateway, every service has to detect, retry, back off and
  fail over on its own, so the same fragile logic is copied across the codebase.
- **Tight coupling to one provider.** Switching from OpenAI to Anthropic, or adding one as
  a fallback, means changing every SDK call, endpoint URL, request/response mapping and
  error-handling path in the application. API keys end up scattered across each service's
  environment variables or secrets managers.
- **Unbounded cost and abuse.** Nothing stops a buggy or malicious client in a tight loop
  from burning through the whole GPT-4 budget in minutes.

llm-gateway fixes this by putting resilience, provider abstraction, key management, rate
limiting and cost tracking in one place.

## Features

### 1. Per-key rate limiting

- **Counts tokens, not just requests.** Providers bill per token. A pure limit of 100
  requests/min lets one key send 100 × 40k-token prompts: about $11/min on GPT-4o, or roughly
  $16k/day from one key. Each key gets **two** limits:
  - a **token limit**, which protects cost
  - a **request limit**, which protects the gateway from floods of tiny requests that eat
    connection-pool slots and add latency for other keys
- **Reserve, then reconcile.** Output tokens aren't known until the provider responds.
  Before forwarding, the gateway reserves `input tokens + max_tokens`. After the response,
  it refunds the difference using the provider's reported `usage`. If the client omits
  `max_tokens`, the gateway fills in a default (e.g. 1024) so existing clients keep working
  and every reservation stays bounded.
- **Algorithm: token bucket.** Stores two numbers per key (level + last refill time), so
  memory is O(keys). A sliding-window log would hold about 500k entries for 10k keys at
  50 req/min. The bucket allows bursts after idle periods, which is how LLM clients actually
  behave, while enforcing a long-term average, and it handles requests of different token
  costs naturally. Anthropic's API uses the same algorithm for its own rate limits.
  Example: 100k tokens/min = capacity 100k, refill about 1,667 tokens/sec.

### 2. Response caching with TTL

- **Only `temperature: 0` requests are cached.** A higher temperature means the client wants
  varied output. Returning a cached answer would take that away.
- **Cache key** = SHA-256 of the fields that change the output: `messages`, system prompt,
  `model`, `temperature`, `max_tokens`, plus any other output-affecting parameter
  (`top_p`, `stop`, `tools`). The same prompt sent to a different model never shares an
  entry, because the client is paying for, and expecting, that specific model's answer.
- Entries expire after a configurable TTL.

### 3. Token and cost tracking

- Uses the provider's reported `usage` (input + output tokens) from each response, not an
  estimate.
- Cost = tokens × per-model price, tracked per API key.

### 4. Retries with exponential backoff across providers

- **Retry:** 429 and 5xx, which are transient.
- **Never retry:** other 4xx (400, 401, ...). They fail the same way every time.
- **Exponential backoff with jitter.** Without jitter, every client that failed at the same
  moment retries at the same moment and overloads the provider again (thundering herd).
- If retries against one provider run out, fail over to the next provider.

## Architecture

Request pipeline, in order:

```
client
  │
  ▼
[1] Request-count limit ── over limit ──► 429
  │
  ▼
[2] Cache lookup ── hit ──► return cached response
  │  (miss)
  ▼
[3] Token limit: reserve input + max_tokens ── over limit ──► 429
  │
  ▼
[4] Provider call (retry 429/5xx with backoff + jitter, fail over to next provider)
  │
  ▼
[5] Reconcile: refund unused reservation, record tokens + cost
  │
  ▼
[6] Store in cache (if temperature 0) ──► return response
```

**Why this order:**
- **Request limit before the cache.** A cache hit still uses gateway resources (threads,
  connections), so the request limit has to apply to it.
- **Token limit after the cache.** A cache hit costs no provider tokens, so it shouldn't use
  up token budget.

## Out of scope

- **Full auth.** Callers are identified by a static API key in a request header. No OAuth,
  no user accounts, no key rotation.
- **Streaming responses.** Responses are returned in one piece; no SSE / token streaming.
- **Multi-instance deployment.** State (rate-limit buckets, cache, cost totals) is kept in
  memory in a single instance. No Redis or other shared store, so limits aren't enforced
  across several gateway instances.

## Definition of done

All of these pass as automated tests (`mvn test`) against a mock provider:

1. **Rate limiting:** the same API key sends 101 requests within a minute (limit 100); the
   101st gets HTTP 429.
2. **Caching:** a second identical `temperature: 0` request returns the cached response,
   and the mock provider records no second call.
3. **Cost tracking:** the recorded cost for a key equals the provider's reported `usage`
   × the model's per-token price.
4. **Retries:** the mock provider returns 503, then 200; the client receives the 200.
