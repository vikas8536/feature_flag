package com.example.flags.config;

import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {

    private static FlagConfig boolFlag(List<Rule> rules) {
        return new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, rules);
    }

    @Test void acceptsMinimalFlag() {
        assertDoesNotThrow(() -> ConfigValidator.validate(boolFlag(List.of())));
    }

    @Test void rejectsDefaultValueTypeMismatch() {
        var c = new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of("oops"), true, null, List.of());
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(c));
    }

    @Test void rejectsRuleValueTypeMismatch() {
        var rule = new Rule(List.of(), FlagValue.of(42L), null);
        assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(boolFlag(List.of(rule))));
    }

    @Test void rejectsRuleWithBothValueAndRollout() {
        var r = new Rule(List.of(), FlagValue.of(true),
                new Rollout("user.id", null, 10, FlagValue.of(true)));
        assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(boolFlag(List.of(r))));
    }

    @Test void rejectsRuleWithNeitherValueNorRollout() {
        var r = new Rule(List.of(), null, null);
        assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(boolFlag(List.of(r))));
    }

    @Test void rejectsPercentageOutOfRange() {
        for (int p : new int[]{-1, 101}) {
            var r = new Rule(List.of(), null, new Rollout("user.id", null, p, FlagValue.of(true)));
            assertThrows(ConfigValidationException.class,
                    () -> ConfigValidator.validate(boolFlag(List.of(r))));
        }
    }

    @Test void rejectsRuleAfterEmptyClauseRule() {
        var matchAll = new Rule(List.of(), FlagValue.of(true), null);
        var dead = new Rule(List.of(new Clause("a", Operator.EXISTS, List.of())), FlagValue.of(false), null);
        assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(boolFlag(List.of(matchAll, dead))));
        assertDoesNotThrow(() -> ConfigValidator.validate(boolFlag(List.of(dead, matchAll))));
    }

    @Test void rejectsRelationalWithNonSingleNumericValue() {
        var two = new Rule(List.of(new Clause("age", Operator.GT, List.of(1, 2))), FlagValue.of(true), null);
        var str = new Rule(List.of(new Clause("age", Operator.GT, List.of("x"))), FlagValue.of(true), null);
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(boolFlag(List.of(two))));
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(boolFlag(List.of(str))));
    }

    @Test void rejectsExistsWithValues() {
        var r = new Rule(List.of(new Clause("a", Operator.EXISTS, List.of("x"))), FlagValue.of(true), null);
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(boolFlag(List.of(r))));
    }

    @Test void rejectsBadRegex() {
        var r = new Rule(List.of(new Clause("a", Operator.MATCHES, List.of("[unclosed"))), FlagValue.of(true), null);
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(boolFlag(List.of(r))));
    }

    @Test void normalizesNullBucketingKeyToUserId() {
        var r = new Rule(List.of(), null, new Rollout(null, null, 10, FlagValue.of(true)));
        FlagConfig out = ConfigValidator.validate(boolFlag(List.of(r)));
        assertEquals("user.id", out.rules().get(0).rollout().bucketingKey());
    }

    @Test void rejectsBlankIdentifiers() {
        var c = new FlagConfig(" ", "prod", "acme", FlagType.BOOLEAN, FlagValue.of(false), true, null, List.of());
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(c));
    }
}
