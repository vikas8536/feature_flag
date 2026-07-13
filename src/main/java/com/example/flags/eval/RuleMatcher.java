package com.example.flags.eval;

import com.example.flags.config.Clause;
import com.example.flags.config.Rule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class RuleMatcher {
    private final AttributeResolver resolver;

    public RuleMatcher(AttributeResolver resolver) { this.resolver = resolver; }

    public boolean matches(Rule rule, Map<String, Object> attributes) {
        for (Clause c : rule.clauses()) if (!clauseMatches(c, attributes)) return false;
        return true;
    }

    private boolean clauseMatches(Clause c, Map<String, Object> attributes) {
        Optional<Object> resolved = resolver.resolve(attributes, c.path());
        return switch (c.op()) {
            case EXISTS -> resolved.isPresent();
            case NOT_EXISTS -> resolved.isEmpty();
            default -> resolved.isPresent() && valueMatches(c, resolved.get());
        };
    }

    /** Array attribute → any-of. Negative ops negate over the whole attribute. */
    private boolean valueMatches(Clause c, Object attr) {
        return switch (c.op()) {
            case NOT_EQUALS -> !anyElement(attr, v -> equalsAny(v, c.values()));
            case NOT_IN     -> !anyElement(attr, v -> equalsAny(v, c.values()));
            case EQUALS, IN -> anyElement(attr, v -> equalsAny(v, c.values()));
            case GT  -> anyElement(attr, v -> cmp(v, c) != null && cmp(v, c) > 0);
            case GTE -> anyElement(attr, v -> cmp(v, c) != null && cmp(v, c) >= 0);
            case LT  -> anyElement(attr, v -> cmp(v, c) != null && cmp(v, c) < 0);
            case LTE -> anyElement(attr, v -> cmp(v, c) != null && cmp(v, c) <= 0);
            case CONTAINS    -> anyElement(attr, v -> stringOp(v, c, (s, t) -> s.contains(t)));
            case STARTS_WITH -> anyElement(attr, v -> stringOp(v, c, String::startsWith));
            case ENDS_WITH   -> anyElement(attr, v -> stringOp(v, c, String::endsWith));
            // ponytail: compile-per-eval; precompile in validator if MATCHES gets hot
            case MATCHES     -> anyElement(attr, v -> v instanceof String s
                    && Pattern.compile((String) c.values().get(0)).matcher(s).find());
            case EXISTS, NOT_EXISTS -> throw new IllegalStateException("handled above");
        };
    }

    private interface StringBiPredicate { boolean test(String a, String b); }

    private static boolean stringOp(Object v, Clause c, StringBiPredicate op) {
        return v instanceof String s && c.values().get(0) instanceof String t && op.test(s, t);
    }

    private static Integer cmp(Object v, Clause c) {
        return ValueCompare.numericCompare(v, c.values().get(0));
    }

    private static boolean equalsAny(Object v, List<Object> values) {
        for (Object cand : values) {
            if (ValueCompare.numericEquals(v, cand)) return true;
            if (v.equals(cand)) return true;
        }
        return false;
    }

    private static boolean anyElement(Object attr, java.util.function.Predicate<Object> p) {
        if (attr instanceof List<?> list) {
            for (Object e : list) if (e != null && p.test(e)) return true;
            return false;
        }
        return p.test(attr);
    }
}
