package com.example.flags.api;

import com.example.flags.config.FlagConfig;
import com.example.flags.eval.DottedPathResolver;
import com.example.flags.eval.Evaluator;
import com.example.flags.log.ErrorKind;
import com.example.flags.log.ErrorSink;
import com.example.flags.log.RateLimitedStderrSink;
import com.example.flags.store.ConfigStore;
import java.util.Optional;

public final class FeatureFlagClient {
    private final ConfigStore store;
    private final String environment;
    private final String tenant;
    private final ErrorSink sink;
    private final Evaluator evaluator;

    private FeatureFlagClient(Builder b) {
        this.store = b.store;
        this.environment = b.environment;
        this.tenant = b.tenant;
        this.sink = b.sink;
        this.evaluator = new Evaluator(new DottedPathResolver(), b.sink);
    }

    public boolean boolValue(String flag, EvaluationContext ctx, boolean defaultValue) {
        FlagValue v = value(flag, ctx, FlagType.BOOLEAN);
        return v instanceof FlagValue.BoolValue bv ? bv.value() : defaultValue;
    }

    public String stringValue(String flag, EvaluationContext ctx, String defaultValue) {
        FlagValue v = value(flag, ctx, FlagType.STRING);
        return v instanceof FlagValue.StringValue sv ? sv.value() : defaultValue;
    }

    public long intValue(String flag, EvaluationContext ctx, long defaultValue) {
        FlagValue v = value(flag, ctx, FlagType.INTEGER);
        return v instanceof FlagValue.IntValue iv ? iv.value() : defaultValue;
    }

    /** Never throws. Returns null when the caller's default must be served. */
    private FlagValue value(String flag, EvaluationContext ctx, FlagType requested) {
        try {
            Optional<FlagConfig> cfg = store.current().get(flag, environment, tenant);
            if (cfg.isEmpty()) {
                sink.log(ErrorKind.NOT_FOUND, String.valueOf(flag), environment, tenant, "no config for scope");
                return null;
            }
            if (cfg.get().type() != requested) {
                sink.log(ErrorKind.TYPE_MISMATCH, flag, environment, tenant,
                        "flag is " + cfg.get().type() + ", requested " + requested);
                return null;
            }
            return evaluator.evaluate(cfg.get(), ctx);
        } catch (Throwable t) {
            sink.log(ErrorKind.EVAL_ERROR, String.valueOf(flag), environment, tenant,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private ConfigStore store;
        private String environment;
        private String tenant;
        private ErrorSink sink = new RateLimitedStderrSink();

        public Builder store(ConfigStore store) { this.store = store; return this; }
        public Builder environment(String environment) { this.environment = environment; return this; }
        public Builder tenant(String tenant) { this.tenant = tenant; return this; }
        public Builder errorSink(ErrorSink sink) { this.sink = sink; return this; }

        public FeatureFlagClient build() {
            if (store == null || environment == null || environment.isBlank()
                    || tenant == null || tenant.isBlank())
                throw new IllegalStateException("store, environment, tenant are required");
            return new FeatureFlagClient(this);
        }
    }
}
