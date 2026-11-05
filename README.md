# llm-gateway

A Java 21 / Spring Boot gateway between application code and text-chat providers.
It centralizes caller authentication, request/token limits, caching, retries,
compatible-provider failover, and per-key token/cost accounting.
It also provides per-provider circuit breakers, a shared provider concurrency cap,
and request IDs with timing logs.

This is a working single-instance MVP. The included local mock lets you demonstrate
the complete flow without credentials or paid API calls.

Read [the step-by-step walkthrough](docs/WALKTHROUGH.md) for each feature's purpose,
the files to inspect, and expected verification results.

## Run locally (PowerShell)

Prerequisite: JDK 21 or newer on PATH. Maven is included via the wrapper.
The first Maven run needs internet access for dependencies.

Terminal 1, from the project directory:

```powershell
java scripts/MockProvider.java
```

Terminal 2, from the project directory:

```powershell
$env:GATEWAY_API_KEYS = 'local-demo-key'
$env:PROVIDER_URL = 'http://127.0.0.1:9091/primary'
$env:PROVIDER_MODEL = 'demo-model'
$env:PROVIDER_INPUT_PRICE = '2'
$env:PROVIDER_OUTPUT_PRICE = '8'
.\mvnw.cmd spring-boot:run
```

These are **fictional demo USD prices per million tokens**. Configure current
model prices yourself for real providers. Prices default to zero; zero reported
cost does not mean provider usage is free.

Terminal 3:

```powershell
$headers = @{ 'X-API-Key' = 'local-demo-key' }
$body = @{
    model = 'demo-model'
    messages = @(@{ role = 'user'; content = 'Hello' })
    temperature = 0
    max_tokens = 20
} | ConvertTo-Json -Depth 5

$response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8080/v1/chat/completions' -Method Post -Headers $headers -ContentType 'application/json' -Body $body
$response.Headers
$response.Content
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/v1/usage' -Headers $headers
```

Send the identical request again: `X-Gateway-Cache` changes from `MISS` to `HIT`,
and the mock console prints no second provider call. Stop both processes with
Ctrl+C. Environment variables apply to the terminal where set; `.env` files are
not automatically loaded.

## Features

| Area | Implemented behavior |
| --- | --- |
| Authentication | Required `X-API-Key` allowlist; accepts no keys by default |
| Request limit | Per-key token bucket; default capacity 100, refill 100/minute; includes cache hits and authenticated invalid requests |
| Token limit | Per-key bucket; default capacity/refill 100,000 tokens/minute |
| Reservation | Serialized message characters / 4, rounded up, plus maximum output tokens; default output allowance 1024 |
| Reconciliation | Replace reservation with reported usage; refund reservation on total failure |
| Cache | Explicit `temperature: 0`; SHA-256 of caller + canonical normalized request; caller isolation; 300-second TTL; 1000-entry LRU cap |
| Provider calls | Compatible chat-completions JSON, bearer credential, 15-second connect/read timeouts |
| Retries | 3 total attempts/provider on 429, 5xx, or connection errors/timeouts; exponential backoff with jitter, capped at 5 seconds |
| Failover | Next compatible provider after transient retry exhaustion, using its configured model |
| Circuit breaker | Opens after 3 consecutive failed provider sequences; skips that provider for 30 seconds, then permits one recovery probe |
| Concurrency cap | At most 20 active provider sequences across all callers, including retries/failover; immediate 503 when full; cache hits bypass the cap |
| Tracing | Fresh `X-Request-ID` per HTTP request, forwarded upstream; logs ID, method, known route, status, and duration without prompts/credentials |
| Usage | Actual reported input/output tokens with separately configured input/output prices; hits add no provider usage |
| Health | `GET /actuator/health`, unauthenticated; application health rather than provider readiness |

Buckets refill continuously and allow bursts; they are not fixed calendar-minute
windows. Estimation is approximate. Usage above a reservation creates debt and
blocks further misses until refill. This is not a hard billing cap.

## API

`POST /v1/chat/completions` accepts:

- `model`: must match the first active provider's configured model.
- `messages`: 1–100 messages, with `role` (`system`, `user`, `assistant`) and string
  `content`; at most 100,000 total content characters.
- `max_tokens`: integer 1–100,000, default 1024.
- `temperature`: 0–2, optional. Omission is not cache eligible.
- `top_p`: 0–1, optional.
- `stop`: string or array of 1–4 strings, optional.
- `stream`: only false accepted; removed before forwarding.

Unknown fields are rejected. Tools, images, audio, and streaming are unsupported.
Responses preserve provider JSON, including its actual model, and add
`X-Gateway-Cache` and `X-Gateway-Provider` headers. HTTP responses also carry a
fresh `X-Request-ID`, including handled errors and cache hits. Client-supplied
request IDs are replaced, and provider retries/failover share the generated ID.

`GET /v1/usage` returns only the authenticated caller's totals:

```json
{
  "provider_calls": 1,
  "input_tokens": 10,
  "output_tokens": 5,
  "estimated_cost_usd": 0.000060,
  "remaining_tokens": 99985
}
```

`provider_calls` counts successful, usage-accounted responses, not failed HTTP
attempts. Totals reset on restart. Cost is estimated using supplied rates, not
invoice reconciliation. Failed attempts may be billable but cannot be accounted
for without reported usage. Remaining allowance refills over time.

| Status | Meaning |
| --- | --- |
| 400 | Invalid/unsupported input or model |
| 401 | Missing or unrecognized caller key |
| 429 | Request/token allowance exhausted; conservative `Retry-After: 60` |
| 502 | Nonretryable upstream 4xx, invalid upstream JSON, or missing/invalid usage |
| 503 | Capacity full, circuits open, all provider attempts failed, no providers available, or interruption |

Provider errors are sanitized: raw upstream errors/credentials are not returned.
Malformed input JSON receives Spring MVC's HTTP 400 response.

## Configuration

See [application.properties](src/main/resources/application.properties).

| Environment variable | Purpose |
| --- | --- |
| `GATEWAY_API_KEYS` | Comma-separated caller key allowlist |
| `GATEWAY_BIND_ADDRESS` | Bind address, default `127.0.0.1` |
| `PROVIDER_URL` | Full primary endpoint URL, including path |
| `PROVIDER_API_KEY` | Primary upstream bearer credential |
| `PROVIDER_MODEL` | Public allowed model and primary outbound model |
| `PROVIDER_INPUT_PRICE`, `PROVIDER_OUTPUT_PRICE` | Primary USD per million input/output tokens |
| `FALLBACK_URL`, `FALLBACK_API_KEY`, `FALLBACK_MODEL` | Optional compatible fallback endpoint, credential, mapped model |
| `FALLBACK_INPUT_PRICE`, `FALLBACK_OUTPUT_PRICE` | Fallback USD per million input/output tokens |

Other properties accept standard Spring overrides:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--gateway.requests-per-minute=5 --gateway.cache-ttl-seconds=30'
```

Active providers need an HTTP(S) URL and nonempty model. Configure trusted
endpoints only, using HTTPS remotely. You choose an appropriate fallback model.
This version exposes one primary model route and an ordered fallback chain.

Additional Spring configuration properties:

| Property | Default | Purpose |
| --- | --- | --- |
| `gateway.max-concurrent-requests` | 20 | Maximum active provider sequences; excess work is rejected rather than queued |
| `gateway.circuit-failure-threshold` | 3 | Consecutive failed sequences before a provider's circuit opens |
| `gateway.circuit-cooldown-seconds` | 30 | Open-circuit cooldown before one probe is admitted |

All three must be positive. A sequence includes that provider's retries; failed
attempts do not count separately. A valid response or nonretryable 4xx resets the
failure count because the provider is reachable. Malformed responses count as
failed sequences but still return 502 without immediate retry/failover. A recovery
probe may use the usual retries; other requests skip that provider until it ends.
Successful probes close the circuit; failed probes restart its cooldown.

## Request pipeline

```text
Client + X-API-Key
  -> authenticate + charge request bucket
  -> validate + normalize
  -> caller-isolated cache lookup -> HIT returns immediately
  -> reserve estimated input + max_tokens
  -> acquire provider capacity (or 503)
  -> skip open circuits -> provider call -> retries -> compatible fallback
  -> release provider capacity, including on failure
  -> reconcile actual tokens + configured cost
  -> cache eligible response -> return

Total provider failure -> refund reservation -> sanitized error
```

The request bucket precedes the cache because hits still consume gateway
resources. Token reservation follows the cache because hits use no provider tokens.
State changes share a lock; network I/O runs outside it so slow calls do not
serialize unrelated callers. Outbound HTTP uses Spring's
[RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html).

## Verify and package

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target/llm-gateway-0.0.1-SNAPSHOT.jar
```

Tests use local HTTP mock providers; no paid API keys needed. They cover all six
original acceptance criteria: request limiting, caching, cost accounting, retries,
token reservation, and failover. Additional cases cover auth, TTL/LRU, refunds,
model mapping, validation, token debt, concurrency caps, circuit recovery/stale
completion handling, trace propagation, log privacy, and the HTTP API. Reports:
`target/surefire-reports/`.

## Limits and next steps

- State is in memory and resets on restart. Add persistent/shared storage before
  multi-instance deployment.
- Native Anthropic Messages translation is not implemented. Both endpoints must
  accept the same chat-completions request and usage format.
- No streaming, tools, multimodal requests, OAuth, key rotation, or admin UI.
- Simultaneous identical misses can cause duplicate provider calls. No request
  coalescing; cache entry count is bounded but response byte size is not.
- The concurrency cap covers provider sequences, not all HTTP connections or JSON
  parsing. No strict ingress byte limit; add ingress limits before public exposure.
- No upstream `Retry-After` handling. Timeouts/retries multiply
  latency and may repeat billable work after ambiguous network failures.
- No real-provider smoke test has been performed. Validation uses local mocks of
  the documented compatible wire format.

This is a local learning/demo service, not a hardened public gateway.
