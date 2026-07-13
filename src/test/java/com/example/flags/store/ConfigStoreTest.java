package com.example.flags.store;

import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.ConfigValidationException;
import com.example.flags.config.FlagConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {

    private static FlagConfig flag(String name, String env, String tenant, boolean dflt) {
        return new FlagConfig(name, env, tenant, FlagType.BOOLEAN,
                FlagValue.of(dflt), true, null, List.of());
    }

    @Test void setThenGet() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        assertTrue(store.get("f", "prod", "acme").isPresent());
        assertTrue(store.get("f", "dev", "acme").isEmpty());     // env isolation
        assertTrue(store.get("f", "prod", "globex").isEmpty());  // tenant isolation
    }

    @Test void setOverwritesSameScope() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        store.set(flag("f", "prod", "acme", false));
        assertEquals(FlagValue.of(false), store.get("f", "prod", "acme").get().defaultValue());
        assertEquals(1, store.current().size());
    }

    @Test void invalidConfigRejectedAndNothingPublished() {
        var store = new ConfigStore();
        var bad = new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of("wrong type"), true, null, List.of());
        assertThrows(ConfigValidationException.class, () -> store.set(bad));
        assertTrue(store.get("f", "prod", "acme").isEmpty());
    }

    @Test void snapshotIsStableWhileStoreMoves() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        ConfigSnapshot snap = store.current();
        store.set(flag("f", "prod", "acme", false));
        assertEquals(FlagValue.of(true), snap.get("f", "prod", "acme").get().defaultValue());
        assertEquals(FlagValue.of(false), store.current().get("f", "prod", "acme").get().defaultValue());
    }

    @Test void sameFlagDifferentScopesCoexist() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        store.set(flag("f", "dev", "acme", false));
        store.set(flag("f", "prod", "globex", false));
        assertEquals(3, store.current().size());
        assertEquals(FlagValue.of(true), store.get("f", "prod", "acme").get().defaultValue());
    }
}
