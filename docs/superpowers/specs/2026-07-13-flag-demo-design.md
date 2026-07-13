# Flag Demo — Design

Date: 2026-07-13
Status: Approved

## Purpose

A runnable, multi-threaded sample application that exercises `FeatureFlagClient`
against a live-mutating `ConfigSource`. It exists to demonstrate — and prove —
three SDK guarantees that a unit test states but a demo can *show*:

1. **Live propagation with no torn reads.** Reader threads never observe a value
   that was never published, and they converge on a new config with no restart.
2. **Sticky bucketing under a rollout ramp.** As a rollout percentage grows, the
   enabled cohort only grows — no user ever flip-flops out.
3. **Never-throws error contract.** Deleting a flag or swapping its type
   mid-flight surfaces caller defaults and structured logs, never an exception.

Throughput and latency numbers are explicitly **out of scope** — `SltTest`
already owns them, and mixing them in would dilute the three invariants above.

## Placement and execution

New package `com.example.flags.demo` in `src/main`:

| File | Responsibility |
| --- | --- |
| `FlagDemo.java` | Phase script, invariant checks, timeline output. `run()` returns `List<String>` of violations; `main()` prints and exits `1` if non-empty. |
| `ReaderFleet.java` | 8 reader threads. Each records its last-observed value, an escaped-`Throwable` count (must remain 0), and an illegal-value count. |

Run:

```bash
mvn -q compile && java -cp target/classes com.example.flags.demo.FlagDemo
```

No `exec-maven-plugin`. Adding a build plugin to save one line of typing is not
a trade worth making.

The demo reuses `ConfigStore` and the existing `com.example.flags.log.RecordingSink`
(already in `src/main`, so no test-scope juggling) to assert the logging half of
the error contract.

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

At each step the main thread sweeps 10,000 synthetic users (`u-0 … u-9999`)
through the client and collects the enabled set.

Assertions:

- The previous enabled set is a **strict subset** of the current one. Cohorts
  nest; no user ever falls out.

Output: target percentage vs. observed percentage per step.

### P3 — Never-throws error contract

While readers continue calling `stringValue("greeting", …)`, the writer:

1. `delete`s `greeting`, then
2. re-`set`s it as an INTEGER flag.

Assertions:

- Zero `Throwable`s escape into any reader.
- Readers serve the **caller's** default throughout both mutations.
- `RecordingSink` records `NOT_FOUND` (after the delete) and then
  `TYPE_MISMATCH` (after the type swap).

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
  `ConfigSource` surface exactly as an external caller would.
