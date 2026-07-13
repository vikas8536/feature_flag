# Flag Demo — Design

Date: 2026-07-13
Status: Approved

## Purpose

A runnable, multi-threaded sample application that exercises `FeatureFlagClient`
against a live-mutating `ConfigSource`. It exists to demonstrate — and prove —
four SDK guarantees that a unit test states but a demo can *show*:

1. **Live propagation with no torn reads.** Reader threads never observe a value
   that was never published, and they converge on a new config with no restart.
2. **Sticky bucketing under a rollout ramp.** As a rollout percentage grows, the
   enabled cohort only grows — no user ever flip-flops out.
3. **Never-throws error contract.** Deleting a flag or swapping its type
   mid-flight surfaces caller defaults and structured logs, never an exception.
4. **Safe rollout stop/resume.** Calling `ConfigSource.stopRollout` immediately
   serves the flag's `defaultValue` to every caller, regardless of bucketing;
   `resumeRollout` returns the same sticky cohort that was enabled at the time
   of the stop.

Throughput and latency numbers are explicitly **out of scope** — `SltTest`
already owns them, and mixing them in would dilute the four invariants above.

## Placement and execution

New package `com.example.flags.demo` in `src/main`:

| File | Responsibility |
| --- | --- |
| `FlagDemo.java` | Phase script, invariant checks, timeline output. `run()` returns `List<String>` of violations; `main()` prints and exits `1` if non-empty. |
| `ReaderFleet.java` | 8 reader threads, configurable to call `stringValue` or `boolValue`. Each records its last-observed value, the set of all values it ever observed, and an escaped-`Throwable` count (must remain 0). |
| `CountingSink.java` | `ErrorSink` that counts per `ErrorKind` and keeps the first detail seen for each. |
| `WriterFleet.java` | Single-thread writer that runs `Runnable` tasks submitted via `submit(...)` and signals completion with a per-task `CountDownLatch`. Used by P2 and P4 so the reader fleet can observe the SDK's atomic-snapshot propagation concurrently with the writes. |

Run:

```bash
mvn -q compile && java -cp target/classes com.example.flags.demo.FlagDemo
```

No `exec-maven-plugin`. Adding a build plugin to save one line of typing is not
a trade worth making.

The demo reuses `ConfigStore` and supplies its own `CountingSink` to assert the
logging half of the error contract.

**Why not the existing `RecordingSink`:** it stores entries in a
`CopyOnWriteArrayList`, so every `add` copies the entire backing array. In P3,
eight reader threads spin through a `NOT_FOUND` window generating a log per read
— that is quadratic and would dominate the demo's runtime or exhaust the heap.
`RecordingSink` is sized for tests that produce a handful of entries, not for a
load loop. `CountingSink` is O(1) per log and counting is all the assertions
need.

## Phases

Each phase prints a labeled snapshot and asserts the single invariant it exists
to prove. The script is deterministic and terminates.

### P0 — Setup

Writer `set`s a STRING flag `greeting = "v1"`. Fleet starts. Assert all 8 readers
observe `v1`.

### P1 — Live propagation, no torn reads

Writer cycles `greeting` through generations `v2 … v10`. After each write it
latch-awaits convergence before issuing the next.

Assertions:

- Every reader converges to the new generation before the next write lands.
- Every value a reader ever observes is a **published generation** — never the
  caller-default sentinel, never a value that was never `set`.

A boolean flag cannot express a torn read (both values are legal), so this phase
uses the STRING flag and the published-generation set. An observed value outside
that set means a reader saw a config that never existed. This is the same shape
as `ConcurrencyStressTest.readersNeverSeeIllegalValuesUnderWrites`, run as a
demo rather than a test.

Output: observed propagation latency per step, in microseconds.

### P2 — Sticky bucketing under a rollout ramp

Writer configures `checkout-v2` with a rule carrying a rollout on `user.id`, then
ramps the percentage `0 → 25 → 50 → 100`.

At each step the main thread submits the write to a single-thread `WriterFleet`
and then sweeps 10,000 synthetic users (`u-0 … u-9999`) through the client to
collect the enabled set. In parallel, 8 reader threads spin on
`boolValue("checkout-v2", ctxFor("u-0"), false)` and visibly converge on each
ramp step. The expected per-reader value is computed from `Bucketer.bucket(...)`
at runtime, so the test is robust to hash-algorithm changes.

Assertions:

- The previous enabled set is a **strict subset** of the current one. Cohorts
  nest; no user ever falls out.
- The 8 reader fleet converges on the expected value within 1 second per step
  (a timeout is itself a violation, not a silent pass).

Output: target percentage vs. observed percentage per step, plus the
writer's per-step write latency and the readers' per-step converge latency,
both in microseconds. The latter is the SDK's atomic-snapshot propagation
time observed by 8 concurrent readers.

### P3 — Never-throws error contract

While readers continue calling `stringValue("greeting", …)`, the writer:

1. `delete`s `greeting`, then
2. re-`set`s it as an INTEGER flag.

Assertions:

- Zero `Throwable`s escape into any reader.
- Readers serve the **caller's** default throughout both mutations — and the only
  values they observe during this phase are the last published generation or that
  caller default.
- `CountingSink` records `NOT_FOUND` (after the delete) and then `TYPE_MISMATCH`
  (after the type swap). Both are awaited with the same spin-and-timeout
  discipline as propagation; a timeout is a violation.

### P4 — Safe rollout stop/resume

The writer resets `checkout-v2` (delete + `set` at 50% ACTIVE) and sweeps
10,000 users to record the enabled cohort at 50%. It then calls
`ConfigSource.stopRollout("checkout-v2", "prod", "acme")` and sweeps again
without any other flag mutation. Next, `resumeRollout` is called and the
cohort is swept a third time. Finally, the writer publishes a higher
percentage (75% ACTIVE) and sweeps once more.

The P2 bool-mode reader fleet continues from P2. Writes go through the
`WriterFleet`; the readers' converge latency per step is the live
propagation latency for that write, observed by 8 concurrent readers
in the demo itself (not just asserted sequentially).

This is a sweep-plus-live phase: the sweep runs the cohort assertions
(no user in `u-0..u-9999` ever falls out, the enabled set strictly
grows across the ramp), and the live reader fleet runs the propagation
assertions (8 readers converge on each step within 1 second).

Assertions:

- After `stopRollout`: **zero users enabled** — no user gets the rollout's
  `value`; every user gets the flag's `defaultValue` (false). Bucketing is
  bypassed entirely. The 8 live readers all flip to `"false"` within 1s.
- After `resumeRollout`: the original 50% cohort is a **subset** of the
  resumed cohort (stickiness survives stop). The cohort is the exact same set
  of users in practice (deterministic hash). The 8 live readers all flip to
  the bucketer's expected value within 1s.
- After ramping to 75% post-resume: the original 50% cohort is a **subset** of
  the 75% cohort (nested cohorts after resume; the high-water mark from the
  pre-stop 50% is retained, so 75% > 50% is accepted and the cohort only grows).
  The 8 live readers all flip to the bucketer's expected value within 1s.

Output: per-step writer write latency + reader converge latency, both in
microseconds. The `stopRollout` and `resumeRollout` lines are the live
propagation latency for those new operations.

## Propagation timing

No `Thread.sleep`. After each write the writer spins (`Thread.onSpinWait`) until
every reader's last-observed generation has advanced, bounded by a 1-second
timeout. **A timeout is itself a violation**, not a silent pass. Propagation
latency is therefore measured, not assumed — consistent with the workspace rule
against sleep-and-hope timing.

## Error handling and reporting

`FlagDemo.run()` accumulates violations as strings and returns them; it never
throws and never calls `System.exit`. `main()` is the only place that maps a
non-empty violation list to exit code `1`, printing each violation under a
`FAIL` header. A clean run prints `PASS` and exits `0`.

## Testing

Because `run()` returns violations instead of exiting the JVM, a single JUnit
test asserts that `run()` returns an empty list. The demo is therefore
CI-verifiable with no new tag, profile, or plugin — it runs inside the default
`mvn test`.

## Out of scope

- Throughput / p99 latency reporting (owned by `SltTest`).
- Interactive stdin control (REPL-style `set` / `delete` / `ramp` commands).
- Any change to SDK production code. The demo consumes the existing
  `ConfigSource` surface (including `stopRollout` / `resumeRollout`) exactly as
  an external caller would.
