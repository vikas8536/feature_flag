package com.example.flags.eval;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FlagValue;
import com.example.flags.config.FlagConfig;
import com.example.flags.config.Rollout;
import com.example.flags.config.Rule;
import com.example.flags.log.ErrorKind;
import com.example.flags.log.ErrorSink;
import java.util.Map;
import java.util.Optional;

public final class Evaluator {
    private final AttributeResolver resolver;
    private final RuleMatcher matcher;
    private final ErrorSink sink;

    public Evaluator(AttributeResolver resolver, ErrorSink sink) {
        this.resolver = resolver;
        this.matcher = new RuleMatcher(resolver);
        this.sink = sink;
    }

    /** Never throws: internal errors → flag defaultValue + EVAL_ERROR log. */
    public FlagValue evaluate(FlagConfig config, EvaluationContext ctx) {
        try {
            return doEvaluate(config, ctx);
        } catch (RuntimeException e) {
            sink.log(ErrorKind.EVAL_ERROR, config.flagName(), config.environment(), config.tenant(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return config.defaultValue();
        }
    }

    private FlagValue doEvaluate(FlagConfig config, EvaluationContext ctx) {
        if (!config.enabled())
            return config.offValue() != null ? config.offValue() : config.defaultValue();

        Map<String, Object> attrs = ctx == null ? Map.of() : ctx.attributes();
        for (Rule rule : config.rules()) {
            if (!matcher.matches(rule, attrs)) continue;
            if (rule.rollout() == null) return rule.value();
            return applyRollout(config, rule.rollout(), attrs);   // terminal: no fallthrough to later rules
        }
        return config.defaultValue();
    }

    private FlagValue applyRollout(FlagConfig config, Rollout ro, Map<String, Object> attrs) {
        Optional<Object> subject = resolver.resolve(attrs, ro.bucketingKey());
        String subjectStr = subject.map(String::valueOf).orElse("");
        if (subjectStr.isBlank()) {
            sink.log(ErrorKind.EVAL_ERROR, config.flagName(), config.environment(), config.tenant(),
                    "bucketing key '" + ro.bucketingKey() + "' absent or blank");
            return config.defaultValue();
        }
        String salt = ro.bucketingGroup() != null ? ro.bucketingGroup() : config.flagName();
        return Bucketer.inRollout(salt, config.tenant(), subjectStr, ro.percentage())
                ? ro.value() : config.defaultValue();
    }
}
