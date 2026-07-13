package com.example.flags.eval;

import java.util.Map;
import java.util.Optional;

public interface AttributeResolver {
    Optional<Object> resolve(Map<String, Object> root, String path);   // dotted path; absent on any missing node
}
