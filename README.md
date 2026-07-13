# Feature Flag SDK

An embeddable, in-process feature flag SDK for Java 21: typed flags, targeting
rules, and sticky percentage rollouts scoped by environment and tenant. Reads
are lock-free against an immutable snapshot, updates propagate with zero delay,
and evaluation never throws — a bad config or bad input always resolves to a
sane default.

## Quickstart

```java
import com.example.flags.api.*;
import com.example.flags.config.*;
import com.example.flags.store.ConfigStore;
import java.util.List;
import java.util.Map;

ConfigStore store = new ConfigStore();
store.set(new FlagConfig("checkout-v2", "prod", "acme",
        FlagType.BOOLEAN, FlagValue.of(true), true, null, List.of()));

FeatureFlagClient client = FeatureFlagClient.builder()
        .store(store)
        .environment("prod")
        .tenant("acme")
        .build();

EvaluationContext ctx = EvaluationContext.builder()
        .attribute("user", Map.of("id", "u-1"))
        .build();

boolean on = client.boolValue("checkout-v2", ctx, false);   // caller default = false
String  s  = client.stringValue("greeting", ctx, "hello");
long    n  = client.intValue("max-items", ctx, 10);
```

## Running tests

```bash
mvn test              # unit tests
mvn verify -Pslt      # unit tests + service-level (SLT) performance tests
```

## Design decisions

- **SDK consumes the store through the given interface** — the client
  read-throughs `ConfigSource.get(flag, env, tenant)` on every evaluation (the
  requirement's `set`/`get` surface, extended with the tenant dimension). No
  private snapshot API leaks into the consumption path, so a remote-backed
  `ConfigSource` is a drop-in swap.
- **Immutable snapshot in an `AtomicReference`** — inside `ConfigStore`, writes
  swap a fresh snapshot in; `get` dereferences it (one volatile read).
  Propagation is instant (0s) and reads are fully lock-free.
- **Pure-hash sticky bucketing (SHA-256)** — a user's bucket is a deterministic
  function of the hash input, so stickiness holds with zero per-user state at
  any user count.
- **Salt = `bucketingGroup ?: flagName`** — flags roll out independently by
  default; flags sharing a `bucketingGroup` share a bucket, giving nested
  cohorts across percentages (the 10% cohort is a subset of the 20% cohort).
- **Tenant baked into the hash input** — bucketing is independent per tenant;
  the same user lands in different buckets across tenants.
- **Rule match is terminal** — the first matching rule decides. If the user
  falls out of that rule's rollout bucket, the flag's default is served (no
  fall-through to later rules).
- **Two-layer error contract** — the evaluator serves the flag's default on any
  internal error; the client boundary serves the *caller's* default when the
  config is unknowable (missing flag, type mismatch). All errors emit a
  structured, rate-limited log rather than an exception.

## Trade-offs

- **O(flags) snapshot copy per write** — each write copies the scope's flag map.
  Fine at human write rates; switch to nested/persistent maps if writes get hot.
- **SHA-256 over murmur3** — chosen correctness-first for well-distributed
  buckets; swap for a faster non-crypto hash if profiling shows it matters.
- **No delete API** — out of scope. Add `delete(scopeKey)` on `ConfigStore`
  when a flag needs to be retired.

## Measured performance (SLT)

- Evaluation latency: **p99 ~935 ns**
- Throughput: **~4.9M evals/s** across 8 threads
- Config propagation: **~0 ms** (snapshot swap)
