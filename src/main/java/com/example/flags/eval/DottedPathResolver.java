package com.example.flags.eval;

import java.util.Map;
import java.util.Optional;

public final class DottedPathResolver implements AttributeResolver {
    @Override
    public Optional<Object> resolve(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) return Optional.empty();
        Object node = root;
        for (String segment : path.split("\\.", -1)) {
            if (!(node instanceof Map<?, ?> m)) return Optional.empty();
            node = m.get(segment);
            if (node == null) return Optional.empty();
        }
        return Optional.of(node);
    }
}
