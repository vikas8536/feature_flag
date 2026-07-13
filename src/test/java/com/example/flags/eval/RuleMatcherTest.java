package com.example.flags.eval;

import com.example.flags.config.Clause;
import com.example.flags.config.Operator;
import com.example.flags.config.Rule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RuleMatcherTest {
    private final RuleMatcher m = new RuleMatcher(new DottedPathResolver());
    private final Map<String, Object> attrs = Map.of(
            "user", Map.of("id", "u-1", "plan", "pro", "age", 25,
                           "address", Map.of("country", "IN"),
                           "tags", List.of("beta", "internal")));

    private boolean match(String path, Operator op, Object... values) {
        return m.matches(new Rule(List.of(new Clause(path, op, List.of(values))), null, null), attrs);
    }

    @ParameterizedTest
    @CsvSource({
        "EQUALS, IN, true", "EQUALS, US, false",
        "NOT_EQUALS, US, true", "NOT_EQUALS, IN, false",
    })
    void equalityOnStrings(Operator op, String v, boolean expected) {
        assertEquals(expected, match("user.address.country", op, v));
    }

    @Test void inNotIn() {
        assertTrue(match("user.plan", Operator.IN, "pro", "enterprise"));
        assertFalse(match("user.plan", Operator.IN, "free"));
        assertTrue(match("user.plan", Operator.NOT_IN, "free"));
        assertFalse(match("user.plan", Operator.NOT_IN, "pro", "free"));
    }

    @Test void relational() {
        assertTrue(match("user.age", Operator.GT, 18));
        assertFalse(match("user.age", Operator.GT, 25));
        assertTrue(match("user.age", Operator.GTE, 25));
        assertTrue(match("user.age", Operator.LT, 30));
        assertFalse(match("user.age", Operator.LTE, 24));
        assertFalse(match("user.plan", Operator.GT, 18));          // non-numeric attribute → false
    }

    @Test void numericCanonicalization() {
        assertTrue(match("user.age", Operator.EQUALS, 25L));       // Integer attr vs Long clause
        assertTrue(match("user.age", Operator.EQUALS, 25.0));      // vs double
        assertTrue(match("user.age", Operator.GT, 24.5));
    }

    @Test void stringOps() {
        assertTrue(match("user.plan", Operator.CONTAINS, "ro"));
        assertTrue(match("user.plan", Operator.STARTS_WITH, "pr"));
        assertTrue(match("user.plan", Operator.ENDS_WITH, "ro"));
        assertTrue(match("user.plan", Operator.MATCHES, "p.*o"));
        assertFalse(match("user.age", Operator.CONTAINS, "2"));    // non-string attribute → false
    }

    @Test void arrayAnyOf() {
        assertTrue(match("user.tags", Operator.EQUALS, "beta"));
        assertFalse(match("user.tags", Operator.EQUALS, "external"));
        assertTrue(match("user.tags", Operator.CONTAINS, "intern"));
        assertTrue(match("user.tags", Operator.NOT_EQUALS, "external")); // no element equals
        assertFalse(match("user.tags", Operator.NOT_EQUALS, "beta"));
    }

    @Test void existence() {
        assertTrue(match("user.plan", Operator.EXISTS));
        assertFalse(match("user.missing", Operator.EXISTS));
        assertTrue(match("user.missing", Operator.NOT_EXISTS));
        assertFalse(match("user.plan", Operator.NOT_EXISTS));
    }

    @Test void absentMatchesOnlyNotExists() {
        for (Operator op : Operator.values()) {
            if (op == Operator.NOT_EXISTS) continue;
            Object[] vals = switch (op) {
                case EXISTS -> new Object[]{};
                case GT, GTE, LT, LTE -> new Object[]{1};
                default -> new Object[]{"x"};
            };
            assertFalse(match("user.nope", op, vals), op + " matched absent attribute");
        }
    }

    @Test void clausesAreAnded() {
        var rule = new Rule(List.of(
                new Clause("user.address.country", Operator.EQUALS, List.of("IN")),
                new Clause("user.plan", Operator.EQUALS, List.of("pro"))), null, null);
        assertTrue(m.matches(rule, attrs));
        var rule2 = new Rule(List.of(
                new Clause("user.address.country", Operator.EQUALS, List.of("IN")),
                new Clause("user.plan", Operator.EQUALS, List.of("free"))), null, null);
        assertFalse(m.matches(rule2, attrs));
    }

    @Test void emptyClausesMatchAll() {
        assertTrue(m.matches(new Rule(List.of(), null, null), attrs));
        assertTrue(m.matches(new Rule(List.of(), null, null), Map.of()));
    }
}
