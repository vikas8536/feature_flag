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
| Bucket sharing across flags | Salt = `bucketingGroup` if set, else flag name. Flags in the same group within an (env, tenant) **must declare the same percentage** — validated at `set()`, rejected otherwise. No group = independent buckets. |
| Tenancy | Tenant is a scoping dimension like environment. Config keyed by `(environment, tenant, flagName)`. Every pair explicit — **no fallback/inheritance** across tenants or environments. |
| Tenant in hash | Always. `hash(salt : tenantId : subject)` — same user id in two tenants buckets independently. |
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

### Bucketing

```java
long h = sha256(salt + ":" + tenantId + ":" + subject);  // salt = bucketingGroup ?: flagName
int bucket = (int) Long.remainderUnsigned(h, 100);
boolean in = bucket < rollout.percentage;                // monotone: ramp-up never evicts
```

- SHA-256 via `ThreadLocal<MessageDigest>` — stdlib, deterministic across
  JVMs. `// ponytail: ~500ns/eval; inline murmur3_32 if hashing shows in a profile.`
- Absent bucketing-key value → cannot bucket → flag default + error log.
- Properties (test-asserted): ramp 10%→20% evicts nobody; same
  `bucketingGroup` → identical bucket; independent flags at p% overlap ≈ p²;
  same user id across tenants → independent buckets.
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

- **FeatureFlagClient** — `boolValue/stringValue/intValue(flag, ctx, default)`.
  The single `try/catch(Throwable)` boundary: on any error, structured log via
  `ErrorSink` + return caller default. Nothing below swallows exceptions.
- **Evaluator** — pure: `(snapshot, request) → result`. No I/O, clock, locks.
- **RuleMatcher / Bucketer / AttributeResolver** — pure, independently tested.
  `DottedPathResolver` walks nested `Map<String,Object>`/`List`; a JSON-fed
  resolver is a named extension point, not built.
- **ConfigStore** — `AtomicReference<ConfigSnapshot>` (immutable map keyed by
  `ScopeKey(env, tenant, flagName)`). `set()` validates then CAS-publishes
  (`updateAndGet`) — concurrent writers safe. Copy is O(15k), fine at
  human write frequency. `// ponytail: nest env→tenant maps if writes get hot.`
- **ErrorSink** — interface; default impl one-line JSON to stderr. Tests
  assert errors are logged, not just defaulted.
- **FlagValue** — sealed (`BoolValue|StringValue|IntValue`); type mismatch at
  call site → caller default + log, never a ClassCastException escaping.

### Validation at set() (reject, never corrupt)

- Value types match flag `type` (default, offValue, rule values, rollout values).
- `percentage ∈ [0,100]`; rule has exactly one of value/rollout; regexes compile.
- Empty-clause rule must be last (later rules are dead — reject).
- All rollouts sharing a `bucketingGroup` within an (env, tenant) declare the
  same percentage.

## 6. Test plan

| Area | Assertions |
|---|---|
| Flag types | bool/string/int served; type mismatch → caller default + logged |
| Targeting | table-driven per operator; nested paths; missing node → absent; array any-of; empty clauses match all; rule order (first wins) |
| Stickiness | same user → same answer ×1000; ramp-up evicts nobody; distribution ≈ N% over 100k subjects (±1.5%) |
| Bucket sharing | same group → same bucket; no group → overlap ≈ p²; group percentage mismatch rejected at set() |
| Isolation | same flag differs per env and per tenant; same user id across tenants → independent buckets; no cross-scope fallback |
| Default-on-error | missing flag, wrong type, null/empty context, throwing resolver, absent bucketing key — each returns default AND logs |
| Concurrency | N reader threads × M writer threads stress; every observed value legal under some published snapshot; run under `-race`-equivalent (JUnit repeated stress) |

## 7. Milestones

1. Core API skeleton + happy-path (types, store, client, error boundary)
2. Targeting rules + operator table + path resolver
3. Rollouts + stickiness + bucket-sharing semantics
4. Tenancy + environment isolation
5. Error-path hardening (default-on-error matrix)
6. Concurrency stress + README

Commit after each green milestone (conventional messages).
