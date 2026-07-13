# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test

Maven, Java 21 (`maven.compiler.release=21`), JUnit 5. No third-party runtime deps.

```bash
mvn test                                    # unit tests (SLT excluded via surefire excludedGroups=slt)
mvn verify -Pslt                            # unit + service-level perf tests (SltTest)
mvn -Dtest=EvaluatorTest test               # single test class
mvn -Dtest=EvaluatorTest#methodName test    # single test method
```

`SltTest` and `ConcurrencyStressTest` are the perf/race gates; SLT is tagged `slt` and only runs under `-Pslt`.

## Architecture

In-process feature flag SDK. Flags are scoped by `(flagName, environment, tenant)` and evaluate to one of three types (`BOOLEAN`, `STRING`, `INTEGER`).

**Read path** (`FeatureFlagClient.value`, `api/`): every `boolValue`/`stringValue`/`intValue` call read-throughs `ConfigSource.get(flag, env, tenant)` — the client holds a `ConfigSource` *interface*, never a concrete store, so a remote-backed source is a drop-in swap. Client is fixed to one `(environment, tenant)` at build time; the caller only names the flag.

**Store** (`store/`): `ConfigStore` implements `ConfigSource` and holds an immutable `ConfigSnapshot` in an `AtomicReference`. `set`/`delete` build a fresh snapshot and swap it (`updateAndGet`); `get` is one volatile read. Reads are lock-free, propagation is instant. `ConfigValidator.validate` runs on `set`, so illegal configs never reach a snapshot.

**Evaluation** (`eval/`): `Evaluator.doEvaluate` — disabled flag → `offValue ?: defaultValue`; stopped rollout → `defaultValue`; else walk `rules` in order, **first match is terminal** (falling out of that rule's rollout bucket serves the flag's `defaultValue`, it does *not* fall through to later rules). `RuleMatcher` evaluates a rule's ANDed `Clause`s via `Operator` + `ValueCompare`; attributes are looked up with `DottedPathResolver` (`user.id` style).

**Rollout lifecycle**: `FlagConfig.rolloutState` is flag-wide. `STOPPED` bypasses all rules while retaining rollout percentages. `ConfigStore.stopRollout`/`resumeRollout` atomically change state; ordinary `set` cannot change rollout state, so stale writers cannot undo a stop. `ConfigStore.set` validates transitions inside `updateAndGet`: existing rollout percentages cannot decrease, rollout positions cannot disappear/be replaced, and bucketing inputs cannot change. Delete or process restart resets this in-memory history.

**Bucketing** (`Bucketer`): sticky, stateless. `SHA-256(salt + ":" + tenant + ":" + subject)`, first 8 bytes → unsigned mod 100. Salt is `rollout.bucketingGroup ?: flagName` — flags roll out independently by default; sharing a `bucketingGroup` gives nested cohorts (the 10% cohort is a subset of the 20%). Tenant is in the hash input, so bucketing is independent per tenant. **Changing the hash input or digest breaks stickiness for every existing user** — treat `Bucketer` as a compatibility surface.

**Error contract** (`log/`): evaluation never throws. Two layers — `Evaluator` catches and serves the *flag's* `defaultValue`; `FeatureFlagClient` returns the *caller's* default when the config is unknowable (`NOT_FOUND`, `TYPE_MISMATCH`). Every failure emits a structured record to `ErrorSink` (`RateLimitedStderrSink` by default; `RecordingSink` in tests). Adding a failure mode means adding an `ErrorKind`, not an exception.

`FlagValue` is a sealed interface with three records; matching on it is exhaustive — a new flag type touches `FlagType`, `FlagValue`, `ConfigValidator`, and a typed accessor on the client.
