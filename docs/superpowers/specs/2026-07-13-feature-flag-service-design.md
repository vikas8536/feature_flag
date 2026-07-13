# In-Process Feature Flag Service — Design

Date: 2026-07-13
Language: Java 21 (Maven, JUnit 5 — no other dependencies)
Status: Approved

## 1. Problem

An embeddable, in-process feature flag SDK. Given a flag name and an
evaluation context (user, tenant, request attributes), return a value:
boolean, string, or integer. Synchronous, low-latency, never throws to the
caller. Config updatable at runtime with < 5s propagation.

Scale target: 1000 flags × 5 tenants × 3 environments = 15,000 configs
(~15 MB heap), 1M users. Per-user memory: **0 bytes** — bucketing is a pure
hash, no assignment state is ever stored.

## 2. Decisions (resolved ambiguities)

| Question | Decision |
|---|---|
| Targeting rule shape | Flat AND-ed clause list per rule; OR = another rule. First matching rule wins. |
| Attribute access | Dotted JSON path (`user.address.country`) over nested maps/lists. Missing node on path = absent attribute, never an error. Pluggable `AttributeResolver`. |
| Rollout shape | `{percentage, value}` — N% get `value`, the rest get the flag's `defaultValue`. No weighted variants. |
| Rollout placement | Rule gates rollout: a matched rule either serves a fixed `value` or applies its own rollout within the matched cohort. No flag-level fallthrough rollout — "N% of everyone" is a rule with an **empty clause list** (matches all). |
| Bucket sharing across flags | Salt = `bucketingGroup` if set, else flag name. Same group = same bucket number for the same subject; **percentages may differ** — a group member at 10% serves a strict subset of a member at 20% (nested cohorts). No cross-flag validation at `set()` (a same-percentage invariant is unimplementable with single-flag writes: once two members exist, neither could ever change percentage). No group = independent buckets. |
| Tenancy | Tenant is a scoping dimension like environment. Config keyed by `(environment, tenant, flagName)`. Every pair explicit — **no fallback/inheritance** across tenants or environments. |
| Tenant in hash | Always. `hash(salt : tenantId : subject)` — same user id in two tenants buckets independently. tenantId is the client's **scope tenant**, never a context attribute. |
| Client scoping | `(environment, tenant)` bound at `FeatureFlagClient` construction (builder). Call shape stays `boolValue(flag, ctx, default)`. One client per scope; clients are cheap and share the store. |
| Error contract | Two layers. Inner (Evaluator, config in scope): mid-evaluation exception → **flag's `defaultValue`** (type-correct by validation) + log — satisfies the requirement literally. Outer (client): config missing / type mismatch / evaluator itself fails → **caller's default** + log (flag default unknowable or wrong-typed here — justified deviation, stated). |
| Numeric semantics | Before comparison, integral `Number`s (`Byte/Short/Integer/Long`) canonicalize to `long`, others to `double`; integral-vs-integral compares as long, any-double as double (`1 == 1.0` → true). INTEGER flag type is Java `long` internally. |
| Stickiness persistence | None. Pure function of (salt, tenant, subject). Stable across calls, restarts, and SDK instances by construction. |
| Config propagation | Immutable `ConfigSnapshot` in an `AtomicReference`. `set()` validates, copy-on-writes, publishes. Evaluator reads one volatile ref per eval — lock-free, 0s propagation (< 5s budget by construction). |

## 3. Config model

```jsonc
{
  "flagName": "checkout_v2",
  "environment": "prod",
  "tenant": "acme",
  "type": "BOOLEAN",              // BOOLEAN | STRING | INTEGER
  "defaultValue": false,
  "enabled": true,
  "offValue": false,              // served when enabled=false; omit → defaultValue

  "rules": [                      // ordered; first rule whose clauses ALL match wins
    {
      "clauses": [                // flat, AND-ed; empty list = match everyone
        { "path": "user.address.country", "op": "EQUALS", "values": ["IN"] },
        { "path": "user.plan",            "op": "IN",     "values": ["pro", "enterprise"] }
      ],
      "rollout": {
        "bucketingKey":   "user.id",        // default "user.id"
        "bucketingGroup": "checkout_ramp",  // omit → salt = flag name
        "percentage": 20,
        "value": true
      }
    },
    { "clauses": [{ "path": "user.betaOptIn", "op": "EQUALS", "values": [true] }],
      "value": true }             // rule with no rollout → fixed value
  ]
}
```

A rule carries **exactly one** of `value` or `rollout` (validated).

**Operators:** `EQUALS NOT_EQUALS IN NOT_IN GT GTE LT LTE CONTAINS
STARTS_WITH ENDS_WITH MATCHES EXISTS NOT_EXISTS`.
Resolved attribute is an array → clause matches if **any element** matches.
Absent attribute matches only `NOT_EXISTS`; all other operators return false.

Operator semantics:
- `GT/GTE/LT/LTE`: exactly **one** value (validated at `set()`), numeric
  comparison only (canonicalized per the numeric-semantics rule); non-numeric
  attribute or value → clause false. No string ordering.
- `CONTAINS/STARTS_WITH/ENDS_WITH/MATCHES`: non-string attribute value →
  clause false, never `toString()`.
- `EXISTS/NOT_EXISTS`: `values` must be empty (validated at `set()`).
- `EQUALS/IN` on numbers uses the numeric canonicalization rule.

## 4. Evaluation algorithm

```
evaluate(flagName, env, tenant, ctx, callerDefault):
  1. config missing, or flag type ≠ requested type → callerDefault + error log
  2. !enabled                                      → offValue
  3. first rule with all clauses matching (empty clauses = match-all):
       rule.rollout → inBucket ? rollout.value : flag.defaultValue
       else         → rule.value
  4. no rule matched                               → flag.defaultValue
```

**Rule match is terminal.** A matched rule with a rollout serves
`flag.defaultValue` to out-of-bucket subjects — later rules are NOT tried.
Rationale: rule match is a cohort assignment; falling through would let a
subject match two cohorts. Explicitly tested (rule1 rollout out-of-bucket
must not fall into rule2).

**Errors during evaluation** (throwing resolver, bad regex at runtime, etc.):
caught inside the Evaluator where the config is in scope → return the
**flag's `defaultValue`** + structured log. The client's outer
`catch(Throwable)` remains only for config-missing / type-mismatch /
evaluator failure, where the flag default is unknowable → caller's default.

### Bucketing

```java
long h = sha256(salt + ":" + tenantId + ":" + subject);  // salt = bucketingGroup ?: flagName
int bucket = (int) Long.remainderUnsigned(h, 100);
boolean in = bucket < rollout.percentage;                // monotone: ramp-up never evicts
```

- SHA-256 via `ThreadLocal<MessageDigest>` — stdlib, deterministic across
  JVMs. `// ponytail: ~500ns/eval; inline murmur3_32 if hashing shows in a profile.`
- Absent bucketing-key value → cannot bucket → flag default + error log.
- Hash = first 8 bytes of the SHA-256 digest, big-endian, as a long.
- `tenantId` in the hash input is the client's scope tenant.
- Properties (test-asserted): ramp 10%→20% evicts nobody; same
  `bucketingGroup` → identical bucket; group members at different
  percentages → smaller is a strict subset of larger (nested cohorts);
  independent flags at p% overlap ≈ p²; same user id across tenants →
  independent buckets.
- Rules changing who reaches a rollout is not a stickiness violation;
  stickiness is scoped to "given subject reaches this rollout, which side".

## 5. Components

```
api/     FeatureFlagClient  EvaluationContext  FlagType  FlagValue (sealed)
config/  FlagConfig  Rule  Clause  Rollout  Operator  ConfigValidator
store/   ConfigStore  ConfigSnapshot  ScopeKey
eval/    Evaluator  RuleMatcher  Bucketer  AttributeResolver (+DottedPathResolver)
log/     ErrorSink
```

- **FeatureFlagClient** — built via builder binding `(store, environment,
  tenant, errorSink)`; call shape `boolValue/stringValue/intValue(flag, ctx,
  default)`. Outer `try/catch(Throwable)` boundary: structured log via
  `ErrorSink` + caller default. Nothing below swallows exceptions except the
  Evaluator's own config-in-scope catch (see §4).
- **EvaluationContext** — immutable nested `Map<String,Object>` built via
  builder (`.attribute("user", Map.of("id","u-123", ...))`). Rule paths and
  `bucketingKey` resolve against it. Scope tenant is NOT read from it.
- **Evaluator** — pure: `(snapshot, request) → result`. No I/O, clock, locks.
- **RuleMatcher / Bucketer / AttributeResolver** — pure, independently tested.
  `DottedPathResolver` walks nested `Map<String,Object>`/`List`; a JSON-fed
  resolver is a named extension point, not built.
- **ConfigStore** — `AtomicReference<ConfigSnapshot>` (immutable map keyed by
  `ScopeKey(env, tenant, flagName)`). `set()` validates then CAS-publishes
  (`updateAndGet`) — concurrent writers safe. Copy is O(15k), fine at
  human write frequency. `// ponytail: nest env→tenant maps if writes get hot.`
- **ErrorSink** — interface receiving `(ErrorKind, flag, env, tenant,
  detail)`; `ErrorKind = NOT_FOUND | TYPE_MISMATCH | EVAL_ERROR`. Contract:
  implementations must be thread-safe and non-blocking. Default impl:
  one-line JSON to stderr, rate-limited to 1 log per (flag, kind) per minute
  (`ConcurrentHashMap` + timestamp) — an unprovisioned tenant must not
  become a stderr firehose at eval rate. Tests assert errors are logged,
  not just defaulted.
- **FlagValue** — sealed (`BoolValue|StringValue|IntValue`); type mismatch at
  call site → caller default + log, never a ClassCastException escaping.

### Validation at set() (reject, never corrupt)

- Value types match flag `type` (default, offValue, rule values, rollout values).
- `percentage ∈ [0,100]`; rule has exactly one of value/rollout; regexes compile.
- Empty-clause rule must be last (later rules are dead — reject).
- `GT/GTE/LT/LTE` clauses have exactly one numeric value; `EXISTS/NOT_EXISTS`
  have empty `values`.
- No `bucketingGroup` cross-flag validation (dropped: single-flag `set()`
  makes a same-percentage invariant unenforceable — see §2).

## 6. Test plan

| Area | Assertions |
|---|---|
| Flag types | bool/string/int served; type mismatch → caller default + logged |
| Targeting | table-driven per operator; nested paths; missing node → absent; array any-of; empty clauses match all; rule order (first wins) |
| Stickiness | same user → same answer ×1000; ramp-up evicts nobody; distribution ≈ N% over 100k subjects (±1.5%) |
| Bucket sharing | same group → same bucket; different percentages in group → nested subset; no group → overlap ≈ p² |
| Rule terminality | matched rule, out-of-bucket → flag default, later rules not tried |
| Numerics | `Integer 20` vs `Long 20` vs `20.0` equality; GT with string value → false |
| Isolation | same flag differs per env and per tenant; same user id across tenants → independent buckets; no cross-scope fallback |
| Default-on-error | missing flag / wrong type → caller default + NOT_FOUND/TYPE_MISMATCH log; throwing resolver / absent bucketing key / null context → **flag default** + EVAL_ERROR log; sink rate-limiting verified |
| Concurrency | N reader threads × M writer threads stress; every observed value legal under some published snapshot; run under `-race`-equivalent (JUnit repeated stress) |

## 7. Milestones

1. Core API skeleton + happy-path (types, store, client, error boundary)
2. Targeting rules + operator table + path resolver
3. Rollouts + stickiness + bucket-sharing semantics
4. Tenancy + environment isolation
5. Error-path hardening (default-on-error matrix)
6. Concurrency stress + README

Commit after each green milestone (conventional messages).
