package com.example.flags.config;

import com.example.flags.api.FlagValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ConfigValidator {
    private ConfigValidator() {}

    public static FlagConfig validate(FlagConfig c) {
        requireNonBlank(c.flagName(), "flagName");
        requireNonBlank(c.environment(), "environment");
        requireNonBlank(c.tenant(), "tenant");
        if (c.defaultValue() == null || c.defaultValue().type() != c.type())
            throw new ConfigValidationException(c.flagName() + ": defaultValue type mismatch");
        if (c.offValue() != null && c.offValue().type() != c.type())
            throw new ConfigValidationException(c.flagName() + ": offValue type mismatch");
        if (c.rolloutState() == null)
            throw new ConfigValidationException(c.flagName() + ": rolloutState is null");

        List<Rule> rules = c.rules() == null ? List.of() : c.rules();
        List<Rule> normalized = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if ((r.value() == null) == (r.rollout() == null))
                throw new ConfigValidationException(c.flagName() + ": rule " + i + " needs exactly one of value/rollout");
            if (r.value() != null && r.value().type() != c.type())
                throw new ConfigValidationException(c.flagName() + ": rule " + i + " value type mismatch");
            if (r.clauses().isEmpty() && i != rules.size() - 1)
                throw new ConfigValidationException(c.flagName() + ": rule " + i + " matches all; later rules dead");
            for (Clause cl : r.clauses()) validateClause(c.flagName(), cl);
            List<Clause> clauses = new ArrayList<>(r.clauses().size());
            for (Clause cl : r.clauses())
                clauses.add(new Clause(cl.path(), cl.op(), cl.values() == null ? List.of() : List.copyOf(cl.values())));
            normalized.add(new Rule(List.copyOf(clauses),
                    r.value(), r.rollout() == null ? null : normalizeRollout(c, r.rollout())));
        }
        return new FlagConfig(c.flagName(), c.environment(), c.tenant(), c.type(),
                c.defaultValue(), c.enabled(), c.offValue(), List.copyOf(normalized), c.rolloutState());
    }

    public static void validateTransition(FlagConfig previous, FlagConfig next) {
        if (previous.rolloutState() != next.rolloutState())
            throw new ConfigValidationException(next.flagName() + ": rollout state must be changed explicitly");
        for (int i = 0; i < previous.rules().size(); i++) {
            Rollout oldRollout = previous.rules().get(i).rollout();
            if (oldRollout == null) continue;
            if (i >= next.rules().size() || next.rules().get(i).rollout() == null)
                throw transitionError(next, i, "existing rollout cannot be removed or replaced");

            Rollout newRollout = next.rules().get(i).rollout();
            if (!Objects.equals(oldRollout.bucketingKey(), newRollout.bucketingKey())
                    || !Objects.equals(oldRollout.bucketingGroup(), newRollout.bucketingGroup()))
                throw transitionError(next, i, "bucketing inputs cannot change");
            if (newRollout.percentage() < oldRollout.percentage())
                throw transitionError(next, i, "percentage cannot decrease from "
                        + oldRollout.percentage() + " to " + newRollout.percentage());
        }
    }

    private static Rollout normalizeRollout(FlagConfig c, Rollout ro) {
        if (ro.percentage() < 0 || ro.percentage() > 100)
            throw new ConfigValidationException(c.flagName() + ": percentage out of [0,100]");
        if (ro.value() == null || ro.value().type() != c.type())
            throw new ConfigValidationException(c.flagName() + ": rollout value type mismatch");
        String key = (ro.bucketingKey() == null || ro.bucketingKey().isBlank()) ? "user.id" : ro.bucketingKey();
        return new Rollout(key, ro.bucketingGroup(), ro.percentage(), ro.value());
    }

    private static void validateClause(String flag, Clause cl) {
        requireNonBlank(cl.path(), "clause path");
        List<Object> vs = cl.values() == null ? List.of() : cl.values();
        switch (cl.op()) {
            case GT, GTE, LT, LTE -> {
                if (vs.size() != 1 || !(vs.get(0) instanceof Number))
                    throw new ConfigValidationException(flag + ": relational op needs exactly one numeric value");
            }
            case EXISTS, NOT_EXISTS -> {
                if (!vs.isEmpty())
                    throw new ConfigValidationException(flag + ": EXISTS/NOT_EXISTS take no values");
            }
            case MATCHES -> {
                if (vs.size() != 1 || !(vs.get(0) instanceof String s))
                    throw new ConfigValidationException(flag + ": MATCHES needs one string pattern");
                else try { Pattern.compile(s); }
                     catch (PatternSyntaxException e) { throw new ConfigValidationException(flag + ": bad regex: " + e.getMessage()); }
            }
            default -> {
                if (vs.isEmpty())
                    throw new ConfigValidationException(flag + ": " + cl.op() + " needs at least one value");
            }
        }
    }

    private static void requireNonBlank(String s, String what) {
        if (s == null || s.isBlank()) throw new ConfigValidationException(what + " is blank");
    }

    private static ConfigValidationException transitionError(FlagConfig c, int rule, String detail) {
        return new ConfigValidationException(c.flagName() + ": rule " + rule + " " + detail);
    }
}
