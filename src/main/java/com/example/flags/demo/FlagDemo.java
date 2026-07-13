package com.example.flags.demo;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FeatureFlagClient;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.FlagConfig;
import com.example.flags.config.Rollout;
import com.example.flags.config.Rule;
import com.example.flags.log.ErrorKind;
import com.example.flags.store.ConfigStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        try (ReaderFleet fleet = new ReaderFleet(client, GREETING, ctxFor("u-0"), READERS)) {
            phase0Setup(fleet);
            phase1Propagation(fleet);
            phase2StickyRamp();
            phase3NeverThrows(fleet);
            phase4StopResume();
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

    /** P2 — ramp a rollout and prove the enabled cohort only ever grows. */
    private void phase2StickyRamp() {
        System.out.println("P2  sticky ramp: " + CHECKOUT + " rollout over " + USERS + " users");
        Set<String> previous = Set.of();
        int previousPercentage = 0;

        for (int percentage : new int[]{0, 25, 50, 100}) {
            store.set(rolloutFlag(CHECKOUT, percentage));
            Set<String> enabled = enabledUsers();

            if (!enabled.containsAll(previous))
                violations.add("P2: ramping " + previousPercentage + "% -> " + percentage
                        + "% dropped users who were already enabled — bucketing is not sticky");

            System.out.printf("    %3d%% target -> %6.2f%% observed (%d/%d users), previous cohort intact%n",
                    percentage, 100.0 * enabled.size() / USERS, enabled.size(), USERS);

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
     * confirm the cohort only grows.
     */
    private void phase4StopResume() {
        System.out.println("P4  stop/resume: stopping " + CHECKOUT + " mid-flight, then resuming");

        // P2 left checkout-v2 at 100% ACTIVE; reset the in-memory high-water mark so we can
        // start this phase at 50%.
        store.delete(CHECKOUT, ENV, TENANT);
        store.set(rolloutFlag(CHECKOUT, 50));
        Set<String> enabledAt50 = enabledUsers();
        System.out.printf("    50%% active   -> %6.2f%% enabled (%d/%d users)%n",
                100.0 * enabledAt50.size() / USERS, enabledAt50.size(), USERS);

        store.stopRollout(CHECKOUT, ENV, TENANT);
        Set<String> enabledWhileStopped = enabledUsers();
        if (!enabledWhileStopped.isEmpty()) {
            violations.add("P4: stopped rollout still served the rollout's value to "
                    + enabledWhileStopped.size() + " user(s) — first example: "
                    + enabledWhileStopped.iterator().next());
        }
        System.out.printf("    STOPPED      -> %6.2f%% enabled (%d/%d users) — every user gets defaultValue%n",
                100.0 * enabledWhileStopped.size() / USERS, enabledWhileStopped.size(), USERS);

        store.resumeRollout(CHECKOUT, ENV, TENANT);
        Set<String> enabledAfterResume = enabledUsers();
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
        store.set(rolloutFlag(CHECKOUT, 75));
        Set<String> enabledAt75 = enabledUsers();
        Set<String> droppedOnRamp = new HashSet<>(enabledAt50);
        droppedOnRamp.removeAll(enabledAt75);
        if (!droppedOnRamp.isEmpty()) {
            violations.add("P4: post-resume ramp 50% -> 75% dropped " + droppedOnRamp.size()
                    + " user(s) from the original cohort");
        }
        System.out.printf("    75%% active   -> %6.2f%% enabled (%d/%d users) — original cohort intact%n",
                100.0 * enabledAt75.size() / USERS, enabledAt75.size(), USERS);
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
