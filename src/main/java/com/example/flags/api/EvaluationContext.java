package com.example.flags.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EvaluationContext {
    private final Map<String, Object> attributes;

    private EvaluationContext(Map<String, Object> attributes) {
        this.attributes = Collections.unmodifiableMap(attributes);
    }

    public Map<String, Object> attributes() { return attributes; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<String, Object> attrs = new LinkedHashMap<>();
        public Builder attribute(String key, Object value) { attrs.put(key, value); return this; }
        public EvaluationContext build() { return new EvaluationContext(new LinkedHashMap<>(attrs)); }
    }
}
