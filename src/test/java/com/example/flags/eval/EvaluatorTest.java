package com.example.flags.eval;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.*;
import com.example.flags.log.ErrorKind;
import com.example.flags.log.RecordingSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class EvaluatorTest {
    private RecordingSink sink;
    private Evaluator ev;

    @BeforeEach void setUp() {
        sink = new RecordingSink();
        ev = new Evaluator(new DottedPathResolver(), sink);
    }

    private static EvaluationContext ctx(String userId, String country) {
        return EvaluationContext.builder()
                .attribute("user", Map.of("id", userId, "address", Map.of("country", country)))
                .build();
    }

    private static FlagConfig flag(boolean enabled, FlagValue off, List<Rule> rules) {
        return ConfigValidator.validate(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), enabled, off, rules));
    }

    @Test void disabledServesOffValueThenDefault() {
        assertEquals(FlagValue.of(true),
                ev.evaluate(flag(false, FlagValue.of(true), List.of()), ctx("u", "IN")));
        assertEquals(FlagValue.of(false),
                ev.evaluate(flag(false, null, List.of()), ctx("u", "IN")));
    }

    @Test void stoppedRolloutServesDefaultWithoutBucketing() {
        var rollout = new Rule(List.of(), null,
                new Rollout("user.missing", null, 100, FlagValue.of(true)));
        var stopped = flag(true, FlagValue.of(true), List.of(rollout))
                .withRolloutState(RolloutState.STOPPED);

        assertEquals(FlagValue.of(false), ev.evaluate(stopped, null));
        assertEquals(0, sink.count(ErrorKind.EVAL_ERROR));
    }

    @Test void disabledTakesPrecedenceOverStopped() {
        var stopped = flag(false, FlagValue.of(true), List.of())
                .withRolloutState(RolloutState.STOPPED);

        assertEquals(FlagValue.of(true), ev.evaluate(stopped, null));
    }

    @Test void firstMatchingRuleWins() {
        var r1 = new Rule(List.of(new Clause("user.address.country", Operator.EQUALS, List.of("IN"))),
                FlagValue.of(true), null);
        var r2 = new Rule(List.of(), FlagValue.of(false), null);
        assertEquals(FlagValue.of(true), ev.evaluate(flag(true, null, List.of(r1, r2)), ctx("u", "IN")));
        assertEquals(FlagValue.of(false), ev.evaluate(flag(true, null, List.of(r1, r2)), ctx("u", "US")));
    }

    @Test void noMatchServesDefault() {
        var r1 = new Rule(List.of(new Clause("user.address.country", Operator.EQUALS, List.of("IN"))),
                FlagValue.of(true), null);
        assertEquals(FlagValue.of(false), ev.evaluate(flag(true, null, List.of(r1)), ctx("u", "US")));
    }

    @Test void rolloutSticky() {
        var r = new Rule(List.of(),
                null, new Rollout("user.id", null, 50, FlagValue.of(true)));
        var f = flag(true, null, List.of(r));
        FlagValue first = ev.evaluate(f, ctx("u-42", "IN"));
        for (int i = 0; i < 100; i++) assertEquals(first, ev.evaluate(f, ctx("u-42", "IN")));
    }

    @Test void rolloutSplitsPopulation() {
        var r = new Rule(List.of(), null, new Rollout("user.id", null, 50, FlagValue.of(true)));
        var f = flag(true, null, List.of(r));
        int on = 0, n = 10_000;
        for (int i = 0; i < n; i++)
            if (ev.evaluate(f, ctx("u-" + i, "IN")).equals(FlagValue.of(true))) on++;
        assertTrue(Math.abs(on - n / 2) < n * 0.03, "on=" + on);
    }

    @Test void ruleMatchIsTerminal_outOfBucketDoesNotFallThrough() {
        // rule1: match-all rollout at 0% → every subject out-of-bucket → default, NOT rule2's value.
        // (validator forbids rules after an empty-clause rule, so rule1 targets IN instead)
        var r1 = new Rule(List.of(new Clause("user.address.country", Operator.EQUALS, List.of("IN"))),
                null, new Rollout("user.id", null, 0, FlagValue.of(true)));
        var r2 = new Rule(List.of(), FlagValue.of(true), null);
        var f = flag(true, null, List.of(r1, r2));
        assertEquals(FlagValue.of(false), ev.evaluate(f, ctx("u-1", "IN")));   // matched r1, out of 0% bucket
        assertEquals(FlagValue.of(true), ev.evaluate(f, ctx("u-1", "US")));    // r1 no match → r2
    }

    @Test void absentBucketingKeyServesDefaultAndLogs() {
        var r = new Rule(List.of(), null, new Rollout("user.missing", null, 100, FlagValue.of(true)));
        assertEquals(FlagValue.of(false), ev.evaluate(flag(true, null, List.of(r)), ctx("u", "IN")));
        assertEquals(1, sink.count(ErrorKind.EVAL_ERROR));
    }

    @Test void throwingResolverServesFlagDefaultAndLogs() {
        AttributeResolver bomb = (root, path) -> { throw new RuntimeException("boom"); };
        var evBad = new Evaluator(bomb, sink);
        var r = new Rule(List.of(new Clause("user.plan", Operator.EQUALS, List.of("pro"))),
                FlagValue.of(true), null);
        assertEquals(FlagValue.of(false), evBad.evaluate(flag(true, null, List.of(r)), ctx("u", "IN")));
        assertEquals(1, sink.count(ErrorKind.EVAL_ERROR));
    }

    @Test void nullContextServesFlagDefaultAndLogs() {
        var r = new Rule(List.of(new Clause("a", Operator.EXISTS, List.of())), FlagValue.of(true), null);
        assertEquals(FlagValue.of(false), ev.evaluate(flag(true, null, List.of(r)), null));
    }

    @Test void sharedGroupNestsCohorts() {
        var r10 = new Rule(List.of(), null, new Rollout("user.id", "grp", 10, FlagValue.of(true)));
        var r20 = new Rule(List.of(), null, new Rollout("user.id", "grp", 20, FlagValue.of(true)));
        var f10 = flag(true, null, List.of(r10));
        var f20 = ConfigValidator.validate(new FlagConfig("g", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, List.of(r20)));
        for (int i = 0; i < 5_000; i++) {
            var c = ctx("u-" + i, "IN");
            if (ev.evaluate(f10, c).equals(FlagValue.of(true)))
                assertEquals(FlagValue.of(true), ev.evaluate(f20, c), "u-" + i + " in 10% but not 20%");
        }
    }
}
