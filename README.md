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

## Demo

A multi-threaded sample app that exercises the client against a live-mutating store:

```bash
mvn -q compile && java -cp target/classes com.example.flags.demo.FlagDemo
```

Eight reader threads hammer the client while the main thread rewrites the config
underneath them. Each phase asserts one guarantee and prints what it observed:

- **P1** cycles a flag's value under load — readers converge on each new value
  (propagation latency printed per step) and never observe a value that was never
  published.
- **P2** ramps a rollout `0 → 25 → 50 → 100` across 10,000 users — the enabled
  cohort only ever grows, so bucketing is sticky.
- **P3** deletes the flag and then changes its type mid-flight — readers keep
  serving the caller's default, no exception escapes, and `NOT_FOUND` /
  `TYPE_MISMATCH` are logged.
- **P4** calls `ConfigSource.stopRollout` mid-flight, verifies that every user
  receives the flag's `defaultValue` while stopped, then `resumeRollout`s and
  confirms the original cohort is intact and a subsequent ramp to 75% only
  grows the cohort (no flip-flopping out).

Exits 0 on `PASS`, 1 on `FAIL`. `FlagDemoTest` runs the same script under
`mvn test`, so the guarantees are checked in CI too.

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
- **Explicit rollout stop** — publishing a config with
  `rolloutState = STOPPED` immediately serves the flag's `defaultValue` without
  matching rules or bucketing. The retained percentage can later resume at the
  same or a higher value. This is distinct from `enabled = false`, which keeps
  serving `offValue ?: defaultValue`.
- **Non-decreasing rollout updates** — `ConfigStore` atomically rejects a lower
  percentage, rollout removal/replacement, or changed bucketing inputs for an
  existing rollout. The transition is checked against the winning snapshot, so
  stale concurrent writers cannot publish a lower percentage.
- **Two-layer error contract** — the evaluator serves the flag's default on any
  internal error; the client boundary serves the *caller's* default when the
  config is unknowable (missing flag, type mismatch). All errors emit a
  structured, rate-limited log rather than an exception.

## Trade-offs

- **O(flags) snapshot copy per write** — each write copies the scope's flag map.
  Fine at human write rates; switch to nested/persistent maps if writes get hot.
- **SHA-256 over murmur3** — chosen correctness-first for well-distributed
  buckets; swap for a faster non-crypto hash if profiling shows it matters.
- **Delete API** — `ConfigSource.delete(flag, env, tenant)` removes a scoped
  config at runtime (no-op if absent). A deleted flag evaluates to the caller's
  default with a `NOT_FOUND` log, same as a never-configured one. Delete also
  resets that flag's in-memory rollout history, so recreating it may start at a
  lower percentage. Process restart has the same effect; durable control planes
  must persist and enforce their own high-water marks.

## Stopping and resuming a rollout

```java
// Emergency fallback: all users receive defaultValue; current percentage is retained.
store.stopRollout("checkout-v2", "prod", "acme");

// Resume the same sticky cohort after the feature is fixed.
store.resumeRollout("checkout-v2", "prod", "acme");
```

Stopping is flag-wide: fixed-value rules and percentage rollout rules are all
bypassed. To ramp further after resuming, publish the same rollout topology and
bucketing inputs with a higher percentage. Ordinary `set` calls cannot change
rollout state, preventing stale writers from stopping or resuming it
accidentally. The invariant applies to rollout percentage and
bucketing inputs; changing targeting clauses or earlier rules can still change
which users reach that rollout.

## Measured performance (SLT)

- Evaluation latency: **p99 ~935 ns**
- Throughput: **~4.9M evals/s** across 8 threads
- Config propagation: **~0 ms** (snapshot swap)
