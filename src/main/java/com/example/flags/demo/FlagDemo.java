package com.example.flags.demo;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FeatureFlagClient;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.FlagConfig;
import com.example.flags.config.Rollout;
import com.example.flags.config.Rule;
import com.example.flags.eval.Bucketer;
import com.example.flags.log.ErrorKind;
import com.example.flags.store.ConfigStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A multi-threaded sample application that proves four SDK guarantees: live propagation with no
 * torn reads, sticky bucketing under a rollout ramp, the never-throws error contract, and safe
 * rollout stop/resume that preserves the cohort.
 *
 * <p>Run it with:
 * <pre>{@code mvn -q compile && java -cp target/classes com.example.flags.demo.FlagDemo}</pre>
 */
public final class FlagDemo {

    private static final String ENV = "prod";
    private static final String TENANT = "acme";
    private static final String GREETING = "greeting";
    private static final String CHECKOUT = "checkout-v2";
    private static final int READERS = 8;
    private static final int USERS = 10_000;
    private static final int LAST_GENERATION = 10;
    private static final long TIMEOUT_NANOS = 1_000_000_000L;   // 1s; expiry is a violation, not a pass

    private final ConfigStore store = new ConfigStore();
    private final CountingSink sink = new CountingSink();
    private final FeatureFlagClient client = FeatureFlagClient.builder()
            .store(store)
            .environment(ENV)
            .tenant(TENANT)
            .errorSink(sink)
            .build();
    private final List<String> violations = new ArrayList<>();

    public static void main(String[] args) {
        List<String> violations = new FlagDemo().run();
        if (violations.isEmpty()) {
            System.out.println();
            System.out.println("PASS — every invariant held");
            return;
        }
        System.out.println();
        System.out.println("FAIL — " + violations.size() + " invariant violation(s)");
        violations.forEach(v -> System.out.println("  - " + v));
        System.exit(1);
    }

    /** Runs the demo. Never throws, never exits; returns the violations it observed. */
    public List<String> run() {
        try (ReaderFleet greetingFleet = new ReaderFleet(client, GREETING, ctxFor("u-0"), READERS);
             ReaderFleet checkoutFleet = new ReaderFleet(client, CHECKOUT, ctxFor("u-0"), READERS, /*boolMode=*/true);
             WriterFleet writer = new WriterFleet(1)) {
            phase0Setup(greetingFleet);
            phase1Propagation(greetingFleet);
            phase2StickyRamp(checkoutFleet, writer);
            phase3NeverThrows(greetingFleet);
            phase4StopResume(checkoutFleet, writer);
        }
        return List.copyOf(violations);
    }

    /** P0 — publish the first config and start the readers. */
    private void phase0Setup(ReaderFleet fleet) {
        System.out.println("P0  setup: greeting=v1, starting " + READERS + " reader threads");
        store.set(stringFlag(GREETING, "v1"));
        fleet.start();

        if (fleet.awaitAll("v1", TIMEOUT_NANOS) < 0) {
            violations.add("P0: readers did not all observe v1 within 1s");
            return;
        }
        System.out.println("    all " + READERS + " readers observe v1");
    }

    /** P1 — cycle the config under load; readers must converge and must never see an unpublished value. */
    private void phase1Propagation(ReaderFleet fleet) {
        System.out.println("P1  live propagation: cycling greeting v2.." + "v" + LAST_GENERATION + " under load");
        Set<String> published = new HashSet<>(Set.of("v1"));

        for (int generation = 2; generation <= LAST_GENERATION; generation++) {
            String value = "v" + generation;
            // Publish before the write: a reader can never legally observe a value we have not yet listed.
            published.add(value);
            store.set(stringFlag(GREETING, value));

            long nanos = fleet.awaitAll(value, TIMEOUT_NANOS);
            if (nanos < 0) {
                violations.add("P1: " + value + " did not reach all readers within 1s");
                continue;
            }
            System.out.printf("    %-3s propagated to all %d readers in %8.1f us%n",
                    value, READERS, nanos / 1_000.0);
        }

        Set<String> unpublished = new HashSet<>(fleet.observedValues());
        unpublished.removeAll(published);
        if (!unpublished.isEmpty())
            violations.add("P1: readers observed values that were never published (torn read): " + unpublished);
        else
            System.out.println("    every observed value was a published one — no torn reads");

        fleet.clearObserved();
    }

    /**
     * P2 — ramp a rollout and prove the enabled cohort only ever grows. The writes go through
     * a single writer thread; 8 live readers spin on the same flag and visibly converge on
     * each ramp step within microseconds.
     */
    private void phase2StickyRamp(ReaderFleet fleet, WriterFleet writer) {
        System.out.println("P2  sticky ramp: " + CHECKOUT + " rollout over " + USERS + " users");
        Set<String> previous = Set.of();
        int previousPercentage = 0;
        int u0Bucket = Bucketer.bucket(CHECKOUT, TENANT, "u-0");

        // Seed the snapshot on the main thread (synchronously) before starting the reader
        // fleet, so the readers never see a NOT_FOUND window for checkout-v2. The writer
        // thread is also warming up here; the per-step writer latency printed below
        // includes the writer's first-schedule cost on the first iteration.
        store.set(rolloutFlag(CHECKOUT, 0));
        fleet.start();

        for (int percentage : new int[]{0, 25, 50, 100}) {
            long writeNanos = writeAndAwait(writer, () -> store.set(rolloutFlag(CHECKOUT, percentage)),
                    "set " + percentage + "%");

            String expected = u0Bucket < percentage ? "true" : "false";
            long readNanos = fleet.awaitAll(expected, TIMEOUT_NANOS);
            if (readNanos < 0) {
                violations.add("P2: " + expected + " did not reach all readers within 1s");
            }

            Set<String> enabled = enabledUsers();
            if (!enabled.containsAll(previous))
                violations.add("P2: ramping " + previousPercentage + "% -> " + percentage
                        + "% dropped users who were already enabled — bucketing is not sticky");

            System.out.printf("    %3d%% target -> %6.2f%% observed (%d/%d users)%n",
                    percentage, 100.0 * enabled.size() / USERS, enabled.size(), USERS);
            System.out.printf("       writer set %3d%% in %6.1f us; %d readers observed '%s' in %6.1f us%n",
                    percentage, writeNanos / 1_000.0, READERS, expected, readNanos / 1_000.0);

            previous = enabled;
            previousPercentage = percentage;
        }
    }

    /** P3 — delete the flag, then change its type, while readers keep calling stringValue. */
    private void phase3NeverThrows(ReaderFleet fleet) {
        System.out.println("P3  never-throws: deleting greeting, then re-setting it as INTEGER, under load");
        long readsBefore = fleet.reads();

        store.delete(GREETING, ENV, TENANT);
        if (fleet.awaitAll(ReaderFleet.CALLER_DEFAULT, TIMEOUT_NANOS) < 0)
            violations.add("P3: readers did not fall back to the caller default after the delete");
        if (!awaitLog(ErrorKind.NOT_FOUND))
            violations.add("P3: the delete produced no NOT_FOUND log");
        else
            System.out.println("    after delete:    caller default served, NOT_FOUND logged ("
                    + sink.count(ErrorKind.NOT_FOUND) + "x) — " + sink.firstDetail(ErrorKind.NOT_FOUND));

        store.set(new FlagConfig(GREETING, ENV, TENANT, FlagType.INTEGER,
                FlagValue.of(42L), true, null, List.of()));
        if (!awaitLog(ErrorKind.TYPE_MISMATCH))
            violations.add("P3: the type swap produced no TYPE_MISMATCH log");
        if (fleet.awaitAll(ReaderFleet.CALLER_DEFAULT, TIMEOUT_NANOS) < 0)
            violations.add("P3: readers did not serve the caller default across the type swap");
        else
            System.out.println("    after type swap: caller default served, TYPE_MISMATCH logged ("
                    + sink.count(ErrorKind.TYPE_MISMATCH) + "x) — " + sink.firstDetail(ErrorKind.TYPE_MISMATCH));

        Set<String> legal = Set.of("v" + LAST_GENERATION, ReaderFleet.CALLER_DEFAULT);
        Set<String> observed = fleet.observedValues();
        if (!legal.containsAll(observed))
            violations.add("P3: readers observed something other than v" + LAST_GENERATION
                    + " or the caller default: " + observed);
        if (fleet.escapedThrowables() != 0)
            violations.add("P3: " + fleet.escapedThrowables()
                    + " throwable(s) escaped into reader threads — the never-throws contract is broken");
        else
            System.out.println("    " + (fleet.reads() - readsBefore)
                    + " reads across both mutations, 0 exceptions escaped");
    }

    /**
     * P4 — stop a rollout in flight, verify every user gets the flag's defaultValue, then resume
     * and confirm the original cohort is intact. Finally, ramp the resumed rollout higher and
     * confirm the cohort only grows. The writes go through a single writer thread; 8 live
     * readers spin on the same flag and visibly flip through stop/resume within microseconds.
     */
    private void phase4StopResume(ReaderFleet fleet, WriterFleet writer) {
        System.out.println("P4  stop/resume: stopping " + CHECKOUT + " mid-flight, then resuming");
        int u0Bucket = Bucketer.bucket(CHECKOUT, TENANT, "u-0");

        // P2 left checkout-v2 at 100% ACTIVE; reset the in-memory high-water mark so we can
        // start this phase at 50%. The bool-mode reader fleet is already on u-0 from P2.
        long deleteNanos = writeAndAwait(writer, () -> store.delete(CHECKOUT, ENV, TENANT), "delete");
        System.out.printf("       writer %-13s in %6.1f us%n", "delete", deleteNanos / 1_000.0);

        Set<String> enabledAt50 = writeAndAwaitCohort(writer, () -> store.set(rolloutFlag(CHECKOUT, 50)),
                "set 50%", 50, u0Bucket, fleet);
        System.out.printf("    50%% active   -> %6.2f%% enabled (%d/%d users)%n",
                100.0 * enabledAt50.size() / USERS, enabledAt50.size(), USERS);

        Set<String> enabledWhileStopped = writeAndAwaitCohort(writer,
                () -> store.stopRollout(CHECKOUT, ENV, TENANT),
                "stopRollout", 0, u0Bucket, fleet);
        if (!enabledWhileStopped.isEmpty()) {
            violations.add("P4: stopped rollout still served the rollout's value to "
                    + enabledWhileStopped.size() + " user(s) — first example: "
                    + enabledWhileStopped.iterator().next());
        }
        System.out.printf("    STOPPED      -> %6.2f%% enabled (%d/%d users) — every user gets defaultValue%n",
                100.0 * enabledWhileStopped.size() / USERS, enabledWhileStopped.size(), USERS);

        Set<String> enabledAfterResume = writeAndAwaitCohort(writer,
                () -> store.resumeRollout(CHECKOUT, ENV, TENANT),
                "resumeRollout", 50, u0Bucket, fleet);
        Set<String> droppedOnResume = new HashSet<>(enabledAt50);
        droppedOnResume.removeAll(enabledAfterResume);
        if (!droppedOnResume.isEmpty()) {
            violations.add("P4: resuming the rollout dropped " + droppedOnResume.size()
                    + " user(s) from the original 50% cohort — bucketing is not sticky across stop");
        }
        System.out.printf("    ACTIVE       -> %6.2f%% enabled (%d/%d users) — original cohort intact%n",
                100.0 * enabledAfterResume.size() / USERS, enabledAfterResume.size(), USERS);

        // Resume must be at the retained or a higher percentage; 75% passes the validator and
        // the new cohort should still contain the original 50%.
        Set<String> enabledAt75 = writeAndAwaitCohort(writer, () -> store.set(rolloutFlag(CHECKOUT, 75)),
                "set 75%", 75, u0Bucket, fleet);
        Set<String> droppedOnRamp = new HashSet<>(enabledAt50);
        droppedOnRamp.removeAll(enabledAt75);
        if (!droppedOnRamp.isEmpty()) {
            violations.add("P4: post-resume ramp 50% -> 75% dropped " + droppedOnRamp.size()
                    + " user(s) from the original cohort");
        }
        System.out.printf("    75%% active   -> %6.2f%% enabled (%d/%d users) — original cohort intact%n",
                100.0 * enabledAt75.size() / USERS, enabledAt75.size(), USERS);
    }

    /**
     * Submits a write to the writer fleet and waits for the latch. Returns the wall-clock
     * nanoseconds from submit to latch-countdown, which is dominated by the SDK's atomic-
     * snapshot publish and the writer thread's scheduling. Used by P2 and P4 to attribute
     * the write latency vs. the readers' converge latency in the demo output.
     */
    private long writeAndAwait(WriterFleet writer, Runnable write, String label) {
        long start = System.nanoTime();
        CountDownLatch latch = writer.submit(write);
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.nanoTime() - start;
    }

    /**
     * Variant that also awaits reader-fleet convergence on the expected value. The expected
     * value is computed from the bucketer for ACTIVE state; for STOPPED, the SDK bypasses
     * bucketing and serves {@code defaultValue} (false), so we expect "false" regardless of
     * the bucket. The returned {@code Set<String>} is the 10K-user sweep, used by P4 to run
     * its existing cohort-subset assertions.
     */
    private Set<String> writeAndAwaitCohort(WriterFleet writer, Runnable write, String label,
                                            int effectivePercentage, int u0Bucket, ReaderFleet fleet) {
        long writeNanos = writeAndAwait(writer, write, label);
        String expected = "stopRollout".equals(label)
                ? "false"
                : (u0Bucket < effectivePercentage ? "true" : "false");
        long readNanos = fleet.awaitAll(expected, TIMEOUT_NANOS);
        if (readNanos < 0) {
            violations.add("P4: " + label + " did not converge to '" + expected + "' within 1s");
        }
        System.out.printf("       writer %-13s in %6.1f us; %d readers observed '%s' in %6.1f us%n",
                label, writeNanos / 1_000.0, READERS, expected, readNanos / 1_000.0);
        return enabledUsers();
    }

    /** Spins until the sink has recorded this kind at least once. Same timeout discipline as propagation. */
    private boolean awaitLog(ErrorKind kind) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < TIMEOUT_NANOS) {
            if (sink.count(kind) > 0) return true;
            Thread.onSpinWait();
        }
        return false;
    }

    private Set<String> enabledUsers() {
        Set<String> enabled = new HashSet<>();
        for (int i = 0; i < USERS; i++) {
            String user = "u-" + i;
            if (client.boolValue(CHECKOUT, ctxFor(user), false)) enabled.add(user);
        }
        return enabled;
    }

    private static EvaluationContext ctxFor(String userId) {
        return EvaluationContext.builder().attribute("user", Map.of("id", userId)).build();
    }

    private static FlagConfig stringFlag(String name, String value) {
        return new FlagConfig(name, ENV, TENANT, FlagType.STRING,
                FlagValue.of(value), true, null, List.of());
    }

    /**
     * A BOOLEAN flag whose single, clause-less rule sends every user through the bucketer.
     * ConfigValidator only permits a clause-less rule as the last one, and this is the only one.
     */
    private static FlagConfig rolloutFlag(String name, int percentage) {
        Rollout rollout = new Rollout("user.id", null, percentage, FlagValue.of(true));
        return new FlagConfig(name, ENV, TENANT, FlagType.BOOLEAN,
                FlagValue.of(false), true, null,
                List.of(new Rule(List.of(), null, rollout)));
    }
}
