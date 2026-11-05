# Build walkthrough

Each step explains why the code exists, where to look, and how to check it.
The README has complete local startup commands.

## 1. Establish a baseline

**Purpose:** distinguish setup problems from gateway bugs.

**Look at:** `pom.xml` (Java 21 and Spring dependencies),
`LlmGatewayApplication.java` (entry point), and
`LlmGatewayApplicationTests.java` (startup test).

**Check:** run `.\mvnw.cmd test`; look for `BUILD SUCCESS` and zero failures.
The startup test proves Spring can start; the other tests prove gateway behavior.

## 2. Define HTTP endpoints and caller identity

**Purpose:** give clients one URL and keep provider credentials inside the gateway.
The caller key identifies its budget; the upstream bearer key authorizes provider
access. These are separate credentials.

**Look at:** `GatewayController.java`, `GatewayProperties.java`, and
`src/main/resources/application.properties`. The controller delegates to the
service; configuration holds keys, limits, routes, and prices.

**Check:** start the README demo. A chat request without `X-API-Key` returns 401;
the configured demo key returns 200. See the real HTTP tests in `GatewayHttpTests`.

## 3. Validate and normalize input

**Purpose:** reject unsupported requests before spending provider tokens and make
defaults explicit. An omitted output limit must not allow unbounded reservation.

**Look at:** `GatewayService.normalize`. It checks model, text messages, token
limits, sampling values, and unsupported fields, then fills in `max_tokens`.

**Check:** use `max_tokens: -1` or `stream: true`. Expect 400 and no provider call.
See `invalidRequestsCannotReachProvider` and
`defaultsMaxTokensAndForwardsProviderCredential` in `GatewayServiceTests`.

## 4. Limit requests and reserve tokens

**Purpose:** protect gateway capacity and provider usage separately. Counting
requests alone treats tiny and enormous prompts as equivalent.

**Look at:** `GatewayService.chat`, `refill`, and `estimate`. Every authenticated
chat request consumes request allowance. A cache miss reserves estimated input
plus maximum output before it can call the provider. Buckets refill continuously.

**Check:** `request101IsLimitedEvenWhenCached` freezes time, sends 100 requests,
and checks that request 101 gets 429. `tokenReservationBlocksProviderCall` proves
insufficient token allowance prevents provider calls. The concurrent-request test
checks that racing threads cannot spend the request allowance twice. Injected time
lets tests check refill without waiting a minute.

## 5. Cache eligible responses

**Purpose:** avoid duplicate provider latency/cost when clients explicitly request
zero temperature. Zero temperature is a caching policy, not a guarantee that a
provider would always generate identical output.

**Look at:** cache lookup/storage, `canonical`, and `digest` in `GatewayService`.
Keys include caller identity and all normalized parameters. Object keys are sorted
before hashing; message order stays intact. TTL controls staleness; LRU caps entries.

**Check:** send the demo request twice. Look for `X-Gateway-Cache: MISS`, then
`HIT`; the mock console prints only one provider call. Change `max_tokens` or
`stop` and expect a miss. Tests also prove separate callers cannot share cached
responses, entries expire, and old entries are evicted.

## 6. Retry and fail over

**Purpose:** recover from temporary outages without retrying permanent errors.
Backoff reduces pressure and jitter avoids synchronized retry bursts.

**Look at:** `ProviderClient.call` and `pause`. Retry 429, 5xx, and network
failures up to three attempts/provider. Other 4xx stop immediately. A transient
failure that exhausts retries moves to the next compatible provider/model.

**Check manually:** restart the mock with:

```powershell
java scripts/MockProvider.java --fail-primary
```

Before restarting the gateway, add these variables in its terminal:

```powershell
$env:FALLBACK_URL = 'http://127.0.0.1:9091/fallback'
$env:FALLBACK_MODEL = 'backup-model'
$env:FALLBACK_INPUT_PRICE = '2'
$env:FALLBACK_OUTPUT_PRICE = '10'
```

Restarting clears old cache entries so the provider path runs. Send the demo
request: expect three primary 503 calls, one fallback 200, and response header
`X-Gateway-Provider: fallback`. The mock returns fixed JSON; the automated test
also inspects the outbound request to prove it sends `backup-model`.

## 7. Reconcile usage and calculate cost

**Purpose:** actual output is unknown before generation. Replace the reservation
with actual reported usage so small outputs do not consume the whole allowance.

**Look at:** the reconciliation block in `GatewayService.chat` and `usage`.
The formula is `(inputTokens * inputPrice + outputTokens * outputPrice) / 1000000`.
`BigDecimal` avoids binary floating-point money rounding. Prices belong to the
provider that actually answered, including fallback.

**Check:** with the primary demo prices and one uncached response, `/v1/usage`
shows 10 input tokens, 5 output tokens, one successful call, and USD 0.000060.
A cached repeat leaves those totals unchanged. Remaining allowance refills over
time, so its manual value can vary.

Total failure refunds the reservation. Usage above the reservation creates debt
that refill must repay. Look at `exhaustedCallsRefundReservation` and
`usageAboveReservationCreatesDebtRatherThanFreeTokens` for both cases.

## 8. Check the assembled service and package it

**Purpose:** prove HTTP routing, dependency injection, serialization, provider I/O,
and accounting work together; then produce an executable artifact.

**Look at:** `GatewayHttpTests.java`. It starts Spring on a random local port and
sends HTTP requests instead of calling Java methods directly.

**Check:** `.\mvnw.cmd package` should finish with zero test failures and produce
`target/llm-gateway-0.0.1-SNAPSHOT.jar`. The README shows how to launch it.
Read its limitations before public deployment: state is in memory, only one
primary model route is exposed, and providers must share a compatible wire format.

## 9. Pause calls to a failing provider with a circuit breaker

**Purpose:** retries help a single request, but a sustained outage would make every
new request repeat the same expensive failure path. A circuit breaker temporarily
skips that provider so traffic reaches fallback faster.

**Look at:** `ProviderCircuit.java` and its use in `ProviderClient.callWithCapacity`.
After three consecutive failed provider sequences (each including its retries),
the circuit opens for 30 seconds. After cooldown, one sequence probes recovery;
other requests skip the probing provider. Success closes it; failure reopens it.
Nonretryable 4xx indicate reachability and do not open the circuit. Generation
numbers prevent an older in-flight response from undoing a newer state transition.

**Check manually:** use the failing-primary demo from step 6, and restart the
gateway with caching disabled so each request exercises providers:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--gateway.cache-ttl-seconds=0'
```

Send the demo request four times in under 30 seconds. The first three requests
each try primary three times and then fallback. The fourth goes straight to
fallback without another primary call. After 30 seconds, another request probes
primary. Tests use an injected clock to prove opening, cooldown, recovery, and
single-probe behavior without waiting. See `ProviderCircuitTests` and
`openCircuitSkipsFailingPrimaryAndUsesFallback` in `GatewayServiceTests`.

## 10. Bound simultaneous provider work

**Purpose:** per-key rate limits do not stop many keys collectively occupying too
many network connections. A shared cap bounds active provider request sequences.

**Look at:** the `Semaphore` in `ProviderClient.call`. It admits up to
`gateway.max-concurrent-requests` sequences (default 20). A slot is held through
retries, backoff, and failover, then released in `finally`. Excess work gets 503
immediately. The service refunds its token reservation; its request allowance is
still consumed. Cache hits happen earlier and do not need a provider slot.

**Check:** run `.\mvnw.cmd test`. In
`overloadRefundsReservationAndStillAllowsCacheHits`, a local provider is held by
latches while the only slot is occupied. Another caller receives 503, its token
reservation is refunded, and an existing cache hit still succeeds. Releasing the
provider lets a subsequent request succeed. `failedProviderCallReleasesCapacity`
checks that exceptions also return the slot.

## 11. Follow one request through the gateway

**Purpose:** identify a slow or failed request in logs without printing prompts,
responses, or API keys.

**Look at:** `RequestTraceFilter.java`, implemented with Spring's
[OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html),
and the `X-Request-ID` forwarding in `ProviderClient`. The filter creates a fresh
UUID, attaches it to the response, and stores it in thread-local logging context.
Provider retries and fallback receive the same ID. The context is restored in
`finally` so a reused HTTP thread cannot inherit another request's ID.

**Check manually:** inspect `$response.Headers['X-Request-ID']` after sending the
README demo request. Search the gateway console for that ID. Expect one line like:

```text
gateway_request request_id=<uuid> method=POST route=/v1/chat/completions status=200 duration_ms=42
```

A cache hit or unauthorized request has its own ID. Incoming client IDs are
replaced. Logs use fixed route labels and omit arbitrary paths/query strings,
request/response bodies, and keys. `GatewayHttpTests` checks upstream propagation
and fresh response IDs; `RequestTraceFilterTests` checks privacy and context cleanup
even when request processing throws. These are correlation IDs, not a full
distributed tracing system or metrics backend.
