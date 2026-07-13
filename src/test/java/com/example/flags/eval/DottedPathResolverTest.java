package com.example.flags.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class DottedPathResolverTest {
    private final AttributeResolver r = new DottedPathResolver();
    private final Map<String, Object> root = Map.of(
            "user", Map.of(
                    "id", "u-1",
                    "address", Map.of("country", "IN"),
                    "tags", List.of("beta", "pro")),
            "plain", 42);

    @Test void resolvesTopLevel()   { assertEquals(Optional.of(42), r.resolve(root, "plain")); }
    @Test void resolvesNested()     { assertEquals(Optional.of("IN"), r.resolve(root, "user.address.country")); }
    @Test void resolvesList()       { assertEquals(Optional.of(List.of("beta", "pro")), r.resolve(root, "user.tags")); }
    @Test void missingLeafIsAbsent(){ assertTrue(r.resolve(root, "user.address.zip").isEmpty()); }
    @Test void missingMidIsAbsent() { assertTrue(r.resolve(root, "user.profile.age").isEmpty()); }
    @Test void nonMapMidIsAbsent()  { assertTrue(r.resolve(root, "plain.deeper").isEmpty()); }
    @Test void nullRootIsAbsent()   { assertTrue(r.resolve(null, "user.id").isEmpty()); }
    @Test void blankPathIsAbsent()  { assertTrue(r.resolve(root, "").isEmpty()); }
}
