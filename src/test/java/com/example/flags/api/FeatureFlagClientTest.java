package com.example.flags.api;

import com.example.flags.config.*;
import com.example.flags.log.ErrorKind;
import com.example.flags.log.RecordingSink;
import com.example.flags.store.ConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FeatureFlagClientTest {
    private ConfigStore store;
    private RecordingSink sink;
    private FeatureFlagClient client;

    @BeforeEach void setUp() {
        store = new ConfigStore();
        sink = new RecordingSink();
        client = FeatureFlagClient.builder()
                .store(store).environment("prod").tenant("acme").errorSink(sink).build();
    }

    private static EvaluationContext ctx() {
        return EvaluationContext.builder().attribute("user", Map.of("id", "u-1")).build();
    }

    @Test void happyPathAllTypes() {
        store.set(new FlagConfig("b", "prod", "acme", FlagType.BOOLEAN, FlagValue.of(true), true, null, List.of()));
        store.set(new FlagConfig("s", "prod", "acme", FlagType.STRING, FlagValue.of("hello"), true, null, List.of()));
        store.set(new FlagConfig("i", "prod", "acme", FlagType.INTEGER, FlagValue.of(42L), true, null, List.of()));
        assertTrue(client.boolValue("b", ctx(), false));
        assertEquals("hello", client.stringValue("s", ctx(), "fallback"));
        assertEquals(42L, client.intValue("i", ctx(), -1));
    }

    @Test void missingFlagServesCallerDefaultAndLogsNotFound() {
        assertEquals("fallback", client.stringValue("ghost", ctx(), "fallback"));
        assertEquals(1, sink.count(ErrorKind.NOT_FOUND));
    }

    @Test void typeMismatchServesCallerDefaultAndLogs() {
        store.set(new FlagConfig("s", "prod", "acme", FlagType.STRING, FlagValue.of("x"), true, null, List.of()));
        assertFalse(client.boolValue("s", ctx(), false));
        assertEquals(1, sink.count(ErrorKind.TYPE_MISMATCH));
    }

    @Test void scopeIsolation() {
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN, FlagValue.of(true), true, null, List.of()));
        store.set(new FlagConfig("f", "dev", "acme", FlagType.BOOLEAN, FlagValue.of(false), true, null, List.of()));
        var devClient = FeatureFlagClient.builder()
                .store(store).environment("dev").tenant("acme").errorSink(sink).build();
        var otherTenant = FeatureFlagClient.builder()
                .store(store).environment("prod").tenant("globex").errorSink(sink).build();
        assertTrue(client.boolValue("f", ctx(), false));
        assertFalse(devClient.boolValue("f", ctx(), true));            // dev config differs
        assertFalse(otherTenant.boolValue("f", ctx(), false));         // unconfigured tenant → caller default
    }

    @Test void liveUpdateVisibleImmediately() {
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN, FlagValue.of(false), true, null, List.of()));
        assertFalse(client.boolValue("f", ctx(), true));
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN, FlagValue.of(true), true, null, List.of()));
        assertTrue(client.boolValue("f", ctx(), false));
    }

    @Test void neverThrowsEvenOnNulls() {
        assertDoesNotThrow(() -> client.boolValue(null, null, true));
        assertTrue(client.boolValue(null, null, true));
    }

    @Test void builderRequiresScope() {
        assertThrows(IllegalStateException.class,
                () -> FeatureFlagClient.builder().store(store).environment("prod").build());
    }
}
