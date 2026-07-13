# In-Process Feature Flag Service — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Parallel execution:** Tasks are grouped into WAVES. All tasks in a wave are independent and MUST be dispatched as parallel subagents (one Agent call per task, same message). **Subagent model: opus.** Each subagent gets exactly one task section (self-contained) plus the Global Constraints section.
>
> **Cross-session tracker:** `docs/superpowers/plans/TRACKER.md`. Every task's final step updates its row (status, commit hash) and commits. A fresh session reads TRACKER.md to resume.

**Goal:** Embeddable Java feature-flag SDK: typed flags, targeting rules, sticky percentage rollouts, env+tenant scoping, lock-free live updates, never-throws evaluation.

**Architecture:** Immutable `ConfigSnapshot` published via `AtomicReference` (0s propagation); pure `Evaluator` (rules → terminal rollout → default) over pluggable dotted-path attribute resolution; SHA-256 bucketing salted by `bucketingGroup ?: flagName` and scoped by tenant; two-layer error contract (evaluator → flag default, client boundary → caller default).

**Tech Stack:** Java 21, Maven, JUnit 5 (Jupiter + params). No other dependencies.

**Spec:** `docs/superpowers/specs/2026-07-13-feature-flag-service-design.md` — binding. On any conflict, spec wins; report the conflict in the task result.

## Global Constraints

- Java 21, Maven, JUnit 5 only. NO other dependencies.
- Base package `com.example.flags`; source `src/main/java`, tests `src/test/java`.
- Every task: TDD (failing test first), then `mvn -q test` green before commit.
- Conventional commits (`feat:`, `test:`, `chore:`, `docs:`).
- Evaluation path: no locks, no I/O, no clock, no allocation-heavy code.
- INTEGER flag type = Java `long` internally.
- Numeric canonicalization: integral `Number` (`Byte/Short/Integer/Long`) → `long`; other `Number` → `double`; integral-vs-integral compares as long, any-double as double.
- Errors: nothing below the two designated catch points swallows exceptions.
- Case-sensitive everywhere (paths, operators, env/tenant names).
- Each task's last step: update your row in `docs/superpowers/plans/TRACKER.md` (status `done`, commit hash) and include it in the task's final commit.
- Parallel-safety rule: a task ONLY creates/modifies files listed in its **Files** block, plus its TRACKER.md row. Never touch another task's files.

## Waves (dispatch order)

| Wave | Tasks | Parallel? |
|---|---|---|
| 1 | 1 | solo |
| 2 | 2, 3, 4, 5 | 4 subagents at once |
| 3 | 6, 7 | 2 subagents at once |
| 4 | 8 | solo |
| 5 | 9 | solo |
| 6 | 10, 11 | 2 subagents at once |
| 7 | 12 | solo |

---

### Task 1: Maven skeleton + smoke test

**Files:**
- Create: `pom.xml`, `.gitignore`, `src/test/java/com/example/flags/SmokeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: buildable Maven project; `mvn -q test` runs JUnit 5; `mvn verify -Pslt` additionally runs tests tagged `slt`.

- [ ] **Step 1: Write pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>feature-flags</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>5.10.2</junit.version>
    <surefire.excludedGroups>slt</surefire.excludedGroups>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
        <configuration>
          <excludedGroups>${surefire.excludedGroups}</excludedGroups>
        </configuration>
      </plugin>
    </plugins>
  </build>

  <profiles>
    <profile>
      <id>slt</id>
      <properties>
        <surefire.excludedGroups/>
      </properties>
    </profile>
  </profiles>
</project>
```

- [ ] **Step 2: Write .gitignore**

```
target/
*.class
.idea/
*.iml
```

- [ ] **Step 3: Write smoke test**

```java
package com.example.flags;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {
    @Test
    void toolchainWorks() {
        assertTrue(Runtime.version().feature() >= 21);
    }
}
```

- [ ] **Step 4: Run** `mvn -q test` — Expected: BUILD SUCCESS, 1 test passes.

- [ ] **Step 5: Update TRACKER.md row for Task 1, commit**

```bash
git add pom.xml .gitignore src docs/superpowers/plans/TRACKER.md
git commit -m "chore: maven skeleton with junit5 and slt profile"
```

---

### Task 2: API value types + config model + validator

**Files:**
- Create: `src/main/java/com/example/flags/api/FlagType.java`, `api/FlagValue.java`, `api/EvaluationContext.java`, `config/Operator.java`, `config/Clause.java`, `config/Rollout.java`, `config/Rule.java`, `config/FlagConfig.java`, `config/ConfigValidationException.java`, `config/ConfigValidator.java` (all under `com.example.flags`)
- Test: `src/test/java/com/example/flags/config/ConfigValidatorTest.java`, `src/test/java/com/example/flags/api/FlagValueTest.java`

**Interfaces:**
- Consumes: nothing (wave-2 independent).
- Produces (exact, later tasks depend on these):

```java
package com.example.flags.api;
public enum FlagType { BOOLEAN, STRING, INTEGER }

public sealed interface FlagValue permits FlagValue.BoolValue, FlagValue.StringValue, FlagValue.IntValue {
    FlagType type();
    record BoolValue(boolean value) implements FlagValue { public FlagType type() { return FlagType.BOOLEAN; } }
    record StringValue(String value) implements FlagValue { public FlagType type() { return FlagType.STRING; } }
    record IntValue(long value) implements FlagValue { public FlagType type() { return FlagType.INTEGER; } }
    static FlagValue of(boolean v) { return new BoolValue(v); }
    static FlagValue of(String v) { return new StringValue(v); }
    static FlagValue of(long v) { return new IntValue(v); }
}

public final class EvaluationContext {
    public static Builder builder();
    public Map<String, Object> attributes();          // immutable, nested maps/lists
    public static final class Builder {
        public Builder attribute(String key, Object value);
        public EvaluationContext build();
    }
}
```

```java
package com.example.flags.config;
public enum Operator { EQUALS, NOT_EQUALS, IN, NOT_IN, GT, GTE, LT, LTE,
                       CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES, EXISTS, NOT_EXISTS }

public record Clause(String path, Operator op, List<Object> values) {}
public record Rollout(String bucketingKey, String bucketingGroup, int percentage, FlagValue value) {}
    // bucketingKey null → "user.id" applied by validator normalization? NO — validator REJECTS null bucketingKey? No:
    // canonical rule: Rollout.normalized() returns copy with bucketingKey defaulted to "user.id" when null. bucketingGroup nullable.
public record Rule(List<Clause> clauses, FlagValue value, Rollout rollout) {}   // exactly one of value/rollout non-null
public record FlagConfig(String flagName, String environment, String tenant, FlagType type,
                         FlagValue defaultValue, boolean enabled, FlagValue offValue, List<Rule> rules) {}
    // offValue nullable → treated as defaultValue by evaluator; rules may be empty list

public class ConfigValidationException extends RuntimeException { public ConfigValidationException(String msg); }
public final class ConfigValidator {
    public static FlagConfig validate(FlagConfig c);  // returns normalized config or throws ConfigValidationException
}
```

- [ ] **Step 1: Write failing tests** (`ConfigValidatorTest` — table-driven; `FlagValueTest` — type mapping)

```java
package com.example.flags.config;

import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {

    private static FlagConfig boolFlag(List<Rule> rules) {
        return new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, rules);
    }

    @Test void acceptsMinimalFlag() {
        assertDoesNotThrow(() -> ConfigValidator.validate(boolFlag(List.of())));
    }

    @Test void rejectsDefaultValueTypeMismatch() {
        var c = new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of("oops"), true, null, List.of());
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(c));
    }

    @Test void rejectsRuleValueTypeMismatch() {
        var rule = new Rule(List.of(), FlagValue.of(42L), null);
        assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(boolFlag(List.of(rule))));
    }

    @Test void rejectsRuleWithBothValueAndRollout() {
        var r = new Rule(List.of(), FlagValue.of(true),
                new Rollout("user.id", null, 10, FlagValue.of(true)));
        assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(boolFlag(List.of(r))));
    }

    @Test void rejectsRuleWithNeitherValueNorRollout() {
        var r = new Rule(List.of(), null, null);
        assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(boolFlag(List.of(r))));
    }

    @Test void rejectsPercentageOutOfRange() {
        for (int p : new int[]{-1, 101}) {
            var r = new Rule(List.of(), null, new Rollout("user.id", null, p, FlagValue.of(true)));
            assertThrows(ConfigValidationException.class,
                    () -> ConfigValidator.validate(boolFlag(List.of(r))));
        }
    }

    @Test void rejectsRuleAfterEmptyClauseRule() {
        var matchAll = new Rule(List.of(), FlagValue.of(true), null);
        var dead = new Rule(List.of(new Clause("a", Operator.EXISTS, List.of())), FlagValue.of(false), null);
        assertThrows(ConfigValidationException.class,
                () -> ConfigValidator.validate(boolFlag(List.of(matchAll, dead))));
        assertDoesNotThrow(() -> ConfigValidator.validate(boolFlag(List.of(dead, matchAll))));
    }

    @Test void rejectsRelationalWithNonSingleNumericValue() {
        var two = new Rule(List.of(new Clause("age", Operator.GT, List.of(1, 2))), FlagValue.of(true), null);
        var str = new Rule(List.of(new Clause("age", Operator.GT, List.of("x"))), FlagValue.of(true), null);
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(boolFlag(List.of(two))));
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(boolFlag(List.of(str))));
    }

    @Test void rejectsExistsWithValues() {
        var r = new Rule(List.of(new Clause("a", Operator.EXISTS, List.of("x"))), FlagValue.of(true), null);
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(boolFlag(List.of(r))));
    }

    @Test void rejectsBadRegex() {
        var r = new Rule(List.of(new Clause("a", Operator.MATCHES, List.of("[unclosed"))), FlagValue.of(true), null);
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(boolFlag(List.of(r))));
    }

    @Test void normalizesNullBucketingKeyToUserId() {
        var r = new Rule(List.of(), null, new Rollout(null, null, 10, FlagValue.of(true)));
        FlagConfig out = ConfigValidator.validate(boolFlag(List.of(r)));
        assertEquals("user.id", out.rules().get(0).rollout().bucketingKey());
    }

    @Test void rejectsBlankIdentifiers() {
        var c = new FlagConfig(" ", "prod", "acme", FlagType.BOOLEAN, FlagValue.of(false), true, null, List.of());
        assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(c));
    }
}
```

```java
package com.example.flags.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagValueTest {
    @Test void typesMap() {
        assertEquals(FlagType.BOOLEAN, FlagValue.of(true).type());
        assertEquals(FlagType.STRING, FlagValue.of("x").type());
        assertEquals(FlagType.INTEGER, FlagValue.of(7L).type());
    }
}
```

- [ ] **Step 2: Run** `mvn -q test` — Expected: FAIL (classes don't exist / compile error).

- [ ] **Step 3: Implement.** `FlagType`, `FlagValue`, records exactly as in Interfaces block. `EvaluationContext`:

```java
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
```

`ConfigValidator` (single static `validate`, checks in this order; each failure throws `ConfigValidationException` with a message naming flag + reason):

```java
package com.example.flags.config;

import com.example.flags.api.FlagValue;
import java.util.ArrayList;
import java.util.List;
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
            normalized.add(r.rollout() == null ? r : new Rule(r.clauses(), null, normalizeRollout(c, r.rollout())));
        }
        return new FlagConfig(c.flagName(), c.environment(), c.tenant(), c.type(),
                c.defaultValue(), c.enabled(), c.offValue(), List.copyOf(normalized));
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
}
```

- [ ] **Step 4: Run** `mvn -q test` — Expected: PASS.
- [ ] **Step 5: Update TRACKER.md row for Task 2, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "feat: flag value types, config model, config validator"
```

---

### Task 3: AttributeResolver + DottedPathResolver

**Files:**
- Create: `src/main/java/com/example/flags/eval/AttributeResolver.java`, `eval/DottedPathResolver.java`
- Test: `src/test/java/com/example/flags/eval/DottedPathResolverTest.java`

**Interfaces:**
- Consumes: nothing (operates on raw `Map<String,Object>`, NOT EvaluationContext — keeps this task independent of Task 2).
- Produces:

```java
package com.example.flags.eval;
public interface AttributeResolver {
    Optional<Object> resolve(Map<String, Object> root, String path);   // dotted path; absent on any missing node
}
public final class DottedPathResolver implements AttributeResolver {}
```

- [ ] **Step 1: Write failing test**

```java
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
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=DottedPathResolverTest` — Expected: FAIL (compile).
- [ ] **Step 3: Implement**

```java
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
```

- [ ] **Step 4: Run** `mvn -q test -Dtest=DottedPathResolverTest` — Expected: PASS.
- [ ] **Step 5: Update TRACKER.md row for Task 3, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "feat: dotted-path attribute resolver"
```

---

### Task 4: Bucketer

**Files:**
- Create: `src/main/java/com/example/flags/eval/Bucketer.java`
- Test: `src/test/java/com/example/flags/eval/BucketerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:

```java
package com.example.flags.eval;
public final class Bucketer {
    public static int bucket(String salt, String tenant, String subject);        // [0,100)
    public static boolean inRollout(String salt, String tenant, String subject, int percentage);
}
```

Semantics (spec §4): hash input `salt + ":" + tenant + ":" + subject`, SHA-256, first 8 bytes big-endian as long, `Long.remainderUnsigned(h, 100)`; `inRollout = bucket < percentage`.

- [ ] **Step 1: Write failing test**

```java
package com.example.flags.eval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BucketerTest {

    @Test void deterministic() {
        int b = Bucketer.bucket("flagA", "acme", "u-1");
        for (int i = 0; i < 1000; i++) assertEquals(b, Bucketer.bucket("flagA", "acme", "u-1"));
    }

    @Test void inRange() {
        for (int i = 0; i < 10_000; i++) {
            int b = Bucketer.bucket("flagA", "acme", "u-" + i);
            assertTrue(b >= 0 && b < 100);
        }
    }

    @Test void distributionRoughlyUniform() {
        int n = 100_000, in = 0;
        for (int i = 0; i < n; i++) if (Bucketer.inRollout("flagA", "acme", "u-" + i, 20)) in++;
        double pct = 100.0 * in / n;
        assertTrue(Math.abs(pct - 20.0) < 1.5, "got " + pct);
    }

    @Test void rampUpNeverEvicts() {
        for (int i = 0; i < 10_000; i++) {
            String u = "u-" + i;
            if (Bucketer.inRollout("f", "t", u, 10))
                assertTrue(Bucketer.inRollout("f", "t", u, 20));
        }
    }

    @Test void sharedSaltSameBucket_independentSaltsDiffer() {
        int agree = 0;
        for (int i = 0; i < 10_000; i++) {
            String u = "u-" + i;
            assertEquals(Bucketer.bucket("groupG", "t", u), Bucketer.bucket("groupG", "t", u));
            if (Bucketer.bucket("flagA", "t", u) == Bucketer.bucket("flagB", "t", u)) agree++;
        }
        assertTrue(agree < 300, "independent salts agreed " + agree + "/10000");  // ~1% expected
    }

    @Test void nestedCohorts_sameSaltDifferentPercentages() {
        for (int i = 0; i < 10_000; i++) {
            String u = "u-" + i;
            if (Bucketer.inRollout("groupG", "t", u, 10))
                assertTrue(Bucketer.inRollout("groupG", "t", u, 20));
        }
    }

    @Test void tenantScopesBuckets() {
        int agree = 0;
        for (int i = 0; i < 10_000; i++)
            if (Bucketer.bucket("f", "tenantA", "u-" + i) == Bucketer.bucket("f", "tenantB", "u-" + i)) agree++;
        assertTrue(agree < 300, "cross-tenant agreement " + agree + "/10000");
    }

    @Test void percentageEdges() {
        assertFalse(Bucketer.inRollout("f", "t", "u-1", 0));
        assertTrue(Bucketer.inRollout("f", "t", "u-1", 100));
    }
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=BucketerTest` — Expected: FAIL (compile).
- [ ] **Step 3: Implement**

```java
package com.example.flags.eval;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Bucketer {
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    });

    private Bucketer() {}

    // ponytail: SHA-256 ~500ns/eval; swap for inlined murmur3_32 if hashing shows in a profile.
    public static int bucket(String salt, String tenant, String subject) {
        MessageDigest md = SHA256.get();
        md.reset();
        byte[] digest = md.digest((salt + ":" + tenant + ":" + subject).getBytes(StandardCharsets.UTF_8));
        long h = ByteBuffer.wrap(digest, 0, 8).getLong();   // first 8 bytes, big-endian
        return (int) Long.remainderUnsigned(h, 100);
    }

    public static boolean inRollout(String salt, String tenant, String subject, int percentage) {
        return bucket(salt, tenant, subject) < percentage;
    }
}
```

- [ ] **Step 4: Run** `mvn -q test -Dtest=BucketerTest` — Expected: PASS.
- [ ] **Step 5: Update TRACKER.md row for Task 4, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "feat: sticky tenant-scoped percentage bucketer"
```

---

### Task 5: ErrorSink + rate-limited stderr sink

**Files:**
- Create: `src/main/java/com/example/flags/log/ErrorKind.java`, `log/ErrorSink.java`, `log/RateLimitedStderrSink.java`, `log/RecordingSink.java` (RecordingSink in main so other tasks' tests can use it)
- Test: `src/test/java/com/example/flags/log/RateLimitedStderrSinkTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:

```java
package com.example.flags.log;
public enum ErrorKind { NOT_FOUND, TYPE_MISMATCH, EVAL_ERROR }

public interface ErrorSink {   // contract: implementations MUST be thread-safe and non-blocking
    void log(ErrorKind kind, String flag, String environment, String tenant, String detail);
}

public final class RateLimitedStderrSink implements ErrorSink {
    public RateLimitedStderrSink();                          // 60s window
    public RateLimitedStderrSink(long windowMillis, java.util.function.LongSupplier clock); // injectable clock (test)
}

public final class RecordingSink implements ErrorSink {     // test util: thread-safe list of entries
    public record Entry(ErrorKind kind, String flag, String environment, String tenant, String detail) {}
    public java.util.List<Entry> entries();
    public long count(ErrorKind kind);
}
```

- [ ] **Step 1: Write failing test**

```java
package com.example.flags.log;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitedStderrSinkTest {

    @Test void rateLimitsPerFlagAndKind() {
        AtomicLong now = new AtomicLong(0);
        var sink = new RateLimitedStderrSink(60_000, now::get);
        assertTrue(sink.shouldLog(ErrorKind.NOT_FOUND, "f1"));
        assertFalse(sink.shouldLog(ErrorKind.NOT_FOUND, "f1"));   // same window suppressed
        assertTrue(sink.shouldLog(ErrorKind.EVAL_ERROR, "f1"));   // different kind allowed
        assertTrue(sink.shouldLog(ErrorKind.NOT_FOUND, "f2"));    // different flag allowed
        now.set(61_000);
        assertTrue(sink.shouldLog(ErrorKind.NOT_FOUND, "f1"));    // window expired
    }

    @Test void recordingSinkRecords() {
        var rec = new RecordingSink();
        rec.log(ErrorKind.NOT_FOUND, "f", "prod", "acme", "missing");
        assertEquals(1, rec.count(ErrorKind.NOT_FOUND));
        assertEquals("f", rec.entries().get(0).flag());
    }
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=RateLimitedStderrSinkTest` — Expected: FAIL (compile).
- [ ] **Step 3: Implement**

```java
package com.example.flags.log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class RateLimitedStderrSink implements ErrorSink {
    private final long windowMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> lastLogged = new ConcurrentHashMap<>();

    public RateLimitedStderrSink() { this(60_000, System::currentTimeMillis); }

    public RateLimitedStderrSink(long windowMillis, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    // visible for tests
    boolean shouldLog(ErrorKind kind, String flag) {
        String key = kind + ":" + flag;
        long now = clock.getAsLong();
        Long prev = lastLogged.get(key);
        if (prev != null && now - prev < windowMillis) return false;
        return lastLogged.compute(key, (k, v) -> (v != null && now - v < windowMillis) ? v : now) == now;
    }

    @Override
    public void log(ErrorKind kind, String flag, String environment, String tenant, String detail) {
        if (!shouldLog(kind, flag)) return;
        System.err.println("{\"level\":\"error\",\"component\":\"feature-flags\",\"kind\":\"" + kind
                + "\",\"flag\":\"" + flag + "\",\"env\":\"" + environment
                + "\",\"tenant\":\"" + tenant + "\",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
```

```java
package com.example.flags.log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordingSink implements ErrorSink {
    public record Entry(ErrorKind kind, String flag, String environment, String tenant, String detail) {}
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void log(ErrorKind kind, String flag, String environment, String tenant, String detail) {
        entries.add(new Entry(kind, flag, environment, tenant, detail));
    }

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public long count(ErrorKind kind) { return entries.stream().filter(e -> e.kind() == kind).count(); }
}
```

Note: `shouldLog` test is same-package, so package-private visibility works.

- [ ] **Step 4: Run** `mvn -q test -Dtest=RateLimitedStderrSinkTest` — Expected: PASS.
- [ ] **Step 5: Update TRACKER.md row for Task 5, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "feat: error sink with per-flag-kind rate limiting"
```

---

### Task 6: RuleMatcher

**Files:**
- Create: `src/main/java/com/example/flags/eval/RuleMatcher.java`, `eval/ValueCompare.java`
- Test: `src/test/java/com/example/flags/eval/RuleMatcherTest.java`, `eval/ValueCompareTest.java`

**Interfaces:**
- Consumes: Task 2 (`Clause`, `Rule`, `Operator`), Task 3 (`AttributeResolver`).
- Produces:

```java
package com.example.flags.eval;
public final class RuleMatcher {
    public RuleMatcher(AttributeResolver resolver);
    public boolean matches(com.example.flags.config.Rule rule, java.util.Map<String, Object> attributes);
        // empty clauses → true; all clauses must match (AND)
}
final class ValueCompare {   // package-private helper
    static boolean numericEquals(Object a, Object b);        // canonicalization rule from Global Constraints
    static Integer numericCompare(Object a, Object b);       // null when either non-numeric
}
```

Clause semantics (spec §3, binding):
- Absent attribute: matches only `NOT_EXISTS`; every other operator → false.
- Attribute is a `List` → any-of: clause matches if any element matches (applies to all operators except EXISTS/NOT_EXISTS which test the list's presence itself).
- `EQUALS/NOT_EQUALS/IN/NOT_IN`: numeric operands use `numericEquals`; otherwise `Object.equals`. `NOT_EQUALS`/`NOT_IN` are the negation of the corresponding positive on the resolved value (for a list attribute: NOT_EQUALS = no element equals).
- `GT/GTE/LT/LTE`: single numeric clause value (validator guarantees); non-numeric attribute → false.
- `CONTAINS/STARTS_WITH/ENDS_WITH/MATCHES`: attribute must be `String`, else false. Clause value: any single value for the string ops; MATCHES uses `Pattern` compiled per call (`// ponytail: compile-per-eval; precompile in validator if MATCHES gets hot`).
- `EXISTS`: attribute present. `NOT_EXISTS`: absent.

- [ ] **Step 1: Write failing tests** (table-driven core)

```java
package com.example.flags.eval;

import com.example.flags.config.Clause;
import com.example.flags.config.Operator;
import com.example.flags.config.Rule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RuleMatcherTest {
    private final RuleMatcher m = new RuleMatcher(new DottedPathResolver());
    private final Map<String, Object> attrs = Map.of(
            "user", Map.of("id", "u-1", "plan", "pro", "age", 25,
                           "address", Map.of("country", "IN"),
                           "tags", List.of("beta", "internal")));

    private boolean match(String path, Operator op, Object... values) {
        return m.matches(new Rule(List.of(new Clause(path, op, List.of(values))), null, null), attrs);
    }

    @ParameterizedTest
    @CsvSource({
        "EQUALS, IN, true", "EQUALS, US, false",
        "NOT_EQUALS, US, true", "NOT_EQUALS, IN, false",
    })
    void equalityOnStrings(Operator op, String v, boolean expected) {
        assertEquals(expected, match("user.address.country", op, v));
    }

    @Test void inNotIn() {
        assertTrue(match("user.plan", Operator.IN, "pro", "enterprise"));
        assertFalse(match("user.plan", Operator.IN, "free"));
        assertTrue(match("user.plan", Operator.NOT_IN, "free"));
        assertFalse(match("user.plan", Operator.NOT_IN, "pro", "free"));
    }

    @Test void relational() {
        assertTrue(match("user.age", Operator.GT, 18));
        assertFalse(match("user.age", Operator.GT, 25));
        assertTrue(match("user.age", Operator.GTE, 25));
        assertTrue(match("user.age", Operator.LT, 30));
        assertFalse(match("user.age", Operator.LTE, 24));
        assertFalse(match("user.plan", Operator.GT, 18));          // non-numeric attribute → false
    }

    @Test void numericCanonicalization() {
        assertTrue(match("user.age", Operator.EQUALS, 25L));       // Integer attr vs Long clause
        assertTrue(match("user.age", Operator.EQUALS, 25.0));      // vs double
        assertTrue(match("user.age", Operator.GT, 24.5));
    }

    @Test void stringOps() {
        assertTrue(match("user.plan", Operator.CONTAINS, "ro"));
        assertTrue(match("user.plan", Operator.STARTS_WITH, "pr"));
        assertTrue(match("user.plan", Operator.ENDS_WITH, "ro"));
        assertTrue(match("user.plan", Operator.MATCHES, "p.*o"));
        assertFalse(match("user.age", Operator.CONTAINS, "2"));    // non-string attribute → false
    }

    @Test void arrayAnyOf() {
        assertTrue(match("user.tags", Operator.EQUALS, "beta"));
        assertFalse(match("user.tags", Operator.EQUALS, "external"));
        assertTrue(match("user.tags", Operator.CONTAINS, "intern"));
        assertTrue(match("user.tags", Operator.NOT_EQUALS, "external")); // no element equals
        assertFalse(match("user.tags", Operator.NOT_EQUALS, "beta"));
    }

    @Test void existence() {
        assertTrue(match("user.plan", Operator.EXISTS));
        assertFalse(match("user.missing", Operator.EXISTS));
        assertTrue(match("user.missing", Operator.NOT_EXISTS));
        assertFalse(match("user.plan", Operator.NOT_EXISTS));
    }

    @Test void absentMatchesOnlyNotExists() {
        for (Operator op : Operator.values()) {
            if (op == Operator.NOT_EXISTS) continue;
            Object[] vals = switch (op) {
                case EXISTS -> new Object[]{};
                case GT, GTE, LT, LTE -> new Object[]{1};
                default -> new Object[]{"x"};
            };
            assertFalse(match("user.nope", op, vals), op + " matched absent attribute");
        }
    }

    @Test void clausesAreAnded() {
        var rule = new Rule(List.of(
                new Clause("user.address.country", Operator.EQUALS, List.of("IN")),
                new Clause("user.plan", Operator.EQUALS, List.of("pro"))), null, null);
        assertTrue(m.matches(rule, attrs));
        var rule2 = new Rule(List.of(
                new Clause("user.address.country", Operator.EQUALS, List.of("IN")),
                new Clause("user.plan", Operator.EQUALS, List.of("free"))), null, null);
        assertFalse(m.matches(rule2, attrs));
    }

    @Test void emptyClausesMatchAll() {
        assertTrue(m.matches(new Rule(List.of(), null, null), attrs));
        assertTrue(m.matches(new Rule(List.of(), null, null), Map.of()));
    }
}
```

```java
package com.example.flags.eval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueCompareTest {
    @Test void integralEquality()  { assertTrue(ValueCompare.numericEquals(20, 20L)); }
    @Test void mixedEquality()     { assertTrue(ValueCompare.numericEquals(1, 1.0)); }
    @Test void inequality()        { assertFalse(ValueCompare.numericEquals(1, 2)); }
    @Test void nonNumericIsNull()  { assertNull(ValueCompare.numericCompare("a", 1)); }
    @Test void compareWorks() {
        assertTrue(ValueCompare.numericCompare(25, 18L) > 0);
        assertTrue(ValueCompare.numericCompare(24.5, 25) < 0);
        assertEquals(0, ValueCompare.numericCompare(25, 25.0));
    }
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest='RuleMatcherTest,ValueCompareTest'` — Expected: FAIL (compile).
- [ ] **Step 3: Implement**

```java
package com.example.flags.eval;

final class ValueCompare {
    private ValueCompare() {}

    private static boolean isIntegral(Object o) {
        return o instanceof Byte || o instanceof Short || o instanceof Integer || o instanceof Long;
    }

    static boolean numericEquals(Object a, Object b) {
        Integer c = numericCompare(a, b);
        return c != null && c == 0;
    }

    static Integer numericCompare(Object a, Object b) {
        if (!(a instanceof Number na) || !(b instanceof Number nb)) return null;
        if (isIntegral(a) && isIntegral(b)) return Long.compare(na.longValue(), nb.longValue());
        return Double.compare(na.doubleValue(), nb.doubleValue());
    }
}
```

```java
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
```

- [ ] **Step 4: Run** `mvn -q test` — Expected: ALL PASS.
- [ ] **Step 5: Update TRACKER.md row for Task 6, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "feat: rule matcher with operator table and numeric canonicalization"
```

---

### Task 7: ConfigStore + ConfigSnapshot

**Files:**
- Create: `src/main/java/com/example/flags/store/ScopeKey.java`, `store/ConfigSnapshot.java`, `store/ConfigStore.java`
- Test: `src/test/java/com/example/flags/store/ConfigStoreTest.java`

**Interfaces:**
- Consumes: Task 2 (`FlagConfig`, `ConfigValidator`, `ConfigValidationException`).
- Produces:

```java
package com.example.flags.store;
public record ScopeKey(String environment, String tenant, String flagName) {}

public final class ConfigSnapshot {
    public static ConfigSnapshot empty();
    public ConfigSnapshot with(com.example.flags.config.FlagConfig c);   // returns NEW snapshot
    public Optional<com.example.flags.config.FlagConfig> get(String flagName, String environment, String tenant);
    public int size();
}

public final class ConfigStore {
    public void set(com.example.flags.config.FlagConfig c);   // validate (normalized) then CAS-publish; throws ConfigValidationException
    public Optional<com.example.flags.config.FlagConfig> get(String flagName, String environment, String tenant);
    public ConfigSnapshot current();                            // what evaluators read
}
```

Validation runs INSIDE `updateAndGet` (spec: validate-then-CAS race note) — the update function may re-run; validation is pure so re-running is safe.

- [ ] **Step 1: Write failing test**

```java
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
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=ConfigStoreTest` — Expected: FAIL (compile).
- [ ] **Step 3: Implement**

```java
package com.example.flags.store;

import com.example.flags.config.FlagConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ConfigSnapshot {
    private static final ConfigSnapshot EMPTY = new ConfigSnapshot(Map.of());
    private final Map<ScopeKey, FlagConfig> flags;

    private ConfigSnapshot(Map<ScopeKey, FlagConfig> flags) { this.flags = flags; }

    public static ConfigSnapshot empty() { return EMPTY; }

    // ponytail: O(all flags) copy per write; nest env→tenant maps if config writes ever get hot.
    public ConfigSnapshot with(FlagConfig c) {
        Map<ScopeKey, FlagConfig> next = new HashMap<>(flags);
        next.put(new ScopeKey(c.environment(), c.tenant(), c.flagName()), c);
        return new ConfigSnapshot(Map.copyOf(next));
    }

    public Optional<FlagConfig> get(String flagName, String environment, String tenant) {
        return Optional.ofNullable(flags.get(new ScopeKey(environment, tenant, flagName)));
    }

    public int size() { return flags.size(); }
}
```

```java
package com.example.flags.store;

import com.example.flags.config.ConfigValidator;
import com.example.flags.config.FlagConfig;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigStore {
    private final AtomicReference<ConfigSnapshot> snapshot = new AtomicReference<>(ConfigSnapshot.empty());

    /** Validates then publishes atomically. Validation inside updateAndGet: function may re-run on CAS contention; validation is pure. */
    public void set(FlagConfig c) {
        snapshot.updateAndGet(s -> s.with(ConfigValidator.validate(c)));
    }

    public Optional<FlagConfig> get(String flagName, String environment, String tenant) {
        return snapshot.get().get(flagName, environment, tenant);
    }

    public ConfigSnapshot current() { return snapshot.get(); }
}
```

- [ ] **Step 4: Run** `mvn -q test` — Expected: ALL PASS.
- [ ] **Step 5: Update TRACKER.md row for Task 7, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "feat: lock-free config store with immutable snapshots"
```

---

### Task 8: Evaluator

**Files:**
- Create: `src/main/java/com/example/flags/eval/Evaluator.java`
- Test: `src/test/java/com/example/flags/eval/EvaluatorTest.java`

**Interfaces:**
- Consumes: Task 2 (`FlagConfig`, `Rule`, `Rollout`, `FlagValue`, `EvaluationContext`), Task 4 (`Bucketer`), Task 5 (`ErrorSink`, `ErrorKind`, `RecordingSink`), Task 6 (`RuleMatcher`), Task 3 (`DottedPathResolver`).
- Produces:

```java
package com.example.flags.eval;
public final class Evaluator {
    public Evaluator(AttributeResolver resolver, com.example.flags.log.ErrorSink sink);
    /** Never throws. Internal errors → flag's defaultValue + EVAL_ERROR log (spec §4). */
    public com.example.flags.api.FlagValue evaluate(com.example.flags.config.FlagConfig config,
                                                    com.example.flags.api.EvaluationContext ctx);
}
```

Algorithm (spec §4, binding): disabled → offValue (or defaultValue if null); first matching rule terminal — rollout ? (inBucket ? rollout.value : defaultValue) : rule.value; no match → defaultValue. Bucketing subject = resolver.resolve(ctx.attributes(), rollout.bucketingKey()) converted via `String.valueOf`; absent OR blank → cannot bucket → defaultValue + EVAL_ERROR log. Salt = `bucketingGroup != null ? bucketingGroup : flagName`. Tenant for hash = `config.tenant()`. Whole body wrapped in `catch (RuntimeException)` → defaultValue + EVAL_ERROR log.

- [ ] **Step 1: Write failing test**

```java
package com.example.flags.eval;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.*;
import com.example.flags.log.ErrorKind;
import com.example.flags.log.RecordingSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class EvaluatorTest {
    private RecordingSink sink;
    private Evaluator ev;

    @BeforeEach void setUp() {
        sink = new RecordingSink();
        ev = new Evaluator(new DottedPathResolver(), sink);
    }

    private static EvaluationContext ctx(String userId, String country) {
        return EvaluationContext.builder()
                .attribute("user", Map.of("id", userId, "address", Map.of("country", country)))
                .build();
    }

    private static FlagConfig flag(boolean enabled, FlagValue off, List<Rule> rules) {
        return ConfigValidator.validate(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), enabled, off, rules));
    }

    @Test void disabledServesOffValueThenDefault() {
        assertEquals(FlagValue.of(true),
                ev.evaluate(flag(false, FlagValue.of(true), List.of()), ctx("u", "IN")));
        assertEquals(FlagValue.of(false),
                ev.evaluate(flag(false, null, List.of()), ctx("u", "IN")));
    }

    @Test void firstMatchingRuleWins() {
        var r1 = new Rule(List.of(new Clause("user.address.country", Operator.EQUALS, List.of("IN"))),
                FlagValue.of(true), null);
        var r2 = new Rule(List.of(), FlagValue.of(false), null);
        assertEquals(FlagValue.of(true), ev.evaluate(flag(true, null, List.of(r1, r2)), ctx("u", "IN")));
        assertEquals(FlagValue.of(false), ev.evaluate(flag(true, null, List.of(r1, r2)), ctx("u", "US")));
    }

    @Test void noMatchServesDefault() {
        var r1 = new Rule(List.of(new Clause("user.address.country", Operator.EQUALS, List.of("IN"))),
                FlagValue.of(true), null);
        assertEquals(FlagValue.of(false), ev.evaluate(flag(true, null, List.of(r1)), ctx("u", "US")));
    }

    @Test void rolloutSticky() {
        var r = new Rule(List.of(),
                null, new Rollout("user.id", null, 50, FlagValue.of(true)));
        var f = flag(true, null, List.of(r));
        FlagValue first = ev.evaluate(f, ctx("u-42", "IN"));
        for (int i = 0; i < 100; i++) assertEquals(first, ev.evaluate(f, ctx("u-42", "IN")));
    }

    @Test void rolloutSplitsPopulation() {
        var r = new Rule(List.of(), null, new Rollout("user.id", null, 50, FlagValue.of(true)));
        var f = flag(true, null, List.of(r));
        int on = 0, n = 10_000;
        for (int i = 0; i < n; i++)
            if (ev.evaluate(f, ctx("u-" + i, "IN")).equals(FlagValue.of(true))) on++;
        assertTrue(Math.abs(on - n / 2) < n * 0.03, "on=" + on);
    }

    @Test void ruleMatchIsTerminal_outOfBucketDoesNotFallThrough() {
        // rule1: match-all rollout at 0% → every subject out-of-bucket → default, NOT rule2's value.
        // (validator forbids rules after an empty-clause rule, so rule1 targets IN instead)
        var r1 = new Rule(List.of(new Clause("user.address.country", Operator.EQUALS, List.of("IN"))),
                null, new Rollout("user.id", null, 0, FlagValue.of(true)));
        var r2 = new Rule(List.of(), FlagValue.of(true), null);
        var f = flag(true, null, List.of(r1, r2));
        assertEquals(FlagValue.of(false), ev.evaluate(f, ctx("u-1", "IN")));   // matched r1, out of 0% bucket
        assertEquals(FlagValue.of(true), ev.evaluate(f, ctx("u-1", "US")));    // r1 no match → r2
    }

    @Test void absentBucketingKeyServesDefaultAndLogs() {
        var r = new Rule(List.of(), null, new Rollout("user.missing", null, 100, FlagValue.of(true)));
        assertEquals(FlagValue.of(false), ev.evaluate(flag(true, null, List.of(r)), ctx("u", "IN")));
        assertEquals(1, sink.count(ErrorKind.EVAL_ERROR));
    }

    @Test void throwingResolverServesFlagDefaultAndLogs() {
        AttributeResolver bomb = (root, path) -> { throw new RuntimeException("boom"); };
        var evBad = new Evaluator(bomb, sink);
        var r = new Rule(List.of(new Clause("user.plan", Operator.EQUALS, List.of("pro"))),
                FlagValue.of(true), null);
        assertEquals(FlagValue.of(false), evBad.evaluate(flag(true, null, List.of(r)), ctx("u", "IN")));
        assertEquals(1, sink.count(ErrorKind.EVAL_ERROR));
    }

    @Test void nullContextServesFlagDefaultAndLogs() {
        var r = new Rule(List.of(new Clause("a", Operator.EXISTS, List.of())), FlagValue.of(true), null);
        assertEquals(FlagValue.of(false), ev.evaluate(flag(true, null, List.of(r)), null));
    }

    @Test void sharedGroupNestsCohorts() {
        var r10 = new Rule(List.of(), null, new Rollout("user.id", "grp", 10, FlagValue.of(true)));
        var r20 = new Rule(List.of(), null, new Rollout("user.id", "grp", 20, FlagValue.of(true)));
        var f10 = flag(true, null, List.of(r10));
        var f20 = ConfigValidator.validate(new FlagConfig("g", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, List.of(r20)));
        for (int i = 0; i < 5_000; i++) {
            var c = ctx("u-" + i, "IN");
            if (ev.evaluate(f10, c).equals(FlagValue.of(true)))
                assertEquals(FlagValue.of(true), ev.evaluate(f20, c), "u-" + i + " in 10% but not 20%");
        }
    }
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=EvaluatorTest` — Expected: FAIL (compile).
- [ ] **Step 3: Implement**

```java
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
```

- [ ] **Step 4: Run** `mvn -q test` — Expected: ALL PASS.
- [ ] **Step 5: Update TRACKER.md row for Task 8, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "feat: evaluator with terminal rules, sticky rollouts, flag-default-on-error"
```

---

### Task 9: FeatureFlagClient

**Files:**
- Create: `src/main/java/com/example/flags/api/FeatureFlagClient.java`
- Test: `src/test/java/com/example/flags/api/FeatureFlagClientTest.java`

**Interfaces:**
- Consumes: Task 7 (`ConfigStore`), Task 8 (`Evaluator`), Task 5 (`ErrorSink`, `ErrorKind`, `RateLimitedStderrSink`, `RecordingSink`), Task 3 (`DottedPathResolver`).
- Produces (the public SDK surface):

```java
package com.example.flags.api;
public final class FeatureFlagClient {
    public static Builder builder();
    public boolean boolValue(String flag, EvaluationContext ctx, boolean defaultValue);
    public String stringValue(String flag, EvaluationContext ctx, String defaultValue);
    public long intValue(String flag, EvaluationContext ctx, long defaultValue);
    public static final class Builder {
        public Builder store(com.example.flags.store.ConfigStore store);       // required
        public Builder environment(String env);                                 // required
        public Builder tenant(String tenant);                                   // required
        public Builder errorSink(com.example.flags.log.ErrorSink sink);         // optional, default RateLimitedStderrSink
        public FeatureFlagClient build();                                       // throws IllegalStateException on missing required
    }
}
```

Behavior: config missing → caller default + NOT_FOUND; flag type ≠ requested → caller default + TYPE_MISMATCH; else evaluator result unwrapped; outer `catch (Throwable)` → caller default + EVAL_ERROR. Never throws from the three value methods.

- [ ] **Step 1: Write failing test**

```java
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
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=FeatureFlagClientTest` — Expected: FAIL (compile).
- [ ] **Step 3: Implement**

```java
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
```

- [ ] **Step 4: Run** `mvn -q test` — Expected: ALL PASS.
- [ ] **Step 5: Update TRACKER.md row for Task 9, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "feat: feature flag client with never-throws boundary and scope binding"
```

---

### Task 10: Concurrency stress tests

**Files:**
- Create: `src/test/java/com/example/flags/ConcurrencyStressTest.java`

**Interfaces:**
- Consumes: Task 9 public API (`FeatureFlagClient`, `EvaluationContext`), Task 7 (`ConfigStore`), Task 2 (`FlagConfig`, `FlagType`, `FlagValue`).
- Produces: stress test proving reads are safe under concurrent writes.

- [ ] **Step 1: Write the stress test** (it should PASS immediately if Tasks 1–9 are correct; a failure is a real bug — report it, do not weaken the test)

```java
package com.example.flags;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FeatureFlagClient;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.FlagConfig;
import com.example.flags.log.RecordingSink;
import com.example.flags.store.ConfigStore;
import org.junit.jupiter.api.RepeatedTest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyStressTest {

    private static FlagConfig intFlag(long value) {
        return new FlagConfig("counter", "prod", "acme", FlagType.INTEGER,
                FlagValue.of(value), true, null, List.of());
    }

    /** 8 readers hammer evaluations while 2 writers republish config.
     *  Invariant: every observed value was legally published at some point; no exceptions, no torn reads. */
    @RepeatedTest(5)
    void readersNeverSeeIllegalValuesUnderWrites() throws Exception {
        var store = new ConfigStore();
        store.set(intFlag(0));
        var sink = new RecordingSink();
        var client = FeatureFlagClient.builder()
                .store(store).environment("prod").tenant("acme").errorSink(sink).build();
        var ctx = EvaluationContext.builder().attribute("user", Map.of("id", "u-1")).build();

        var stop = new AtomicBoolean(false);
        var failures = new ConcurrentLinkedQueue<String>();
        try (var pool = Executors.newFixedThreadPool(10)) {
            for (int w = 0; w < 2; w++) {
                final int writer = w;
                pool.submit(() -> {
                    long v = writer * 1_000_000L;
                    while (!stop.get()) store.set(intFlag(v++));
                });
            }
            for (int r = 0; r < 8; r++) {
                pool.submit(() -> {
                    while (!stop.get()) {
                        long got = client.intValue("counter", ctx, -1);
                        if (got < 0) failures.add("saw illegal value " + got);
                    }
                });
            }
            Thread.sleep(2_000);
            stop.set(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(0, sink.entries().size(), "no errors expected: " + sink.entries());
    }
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=ConcurrencyStressTest` — Expected: PASS (5 repetitions). If it fails, STOP and report the failure verbatim; do not weaken assertions.
- [ ] **Step 3: Run full suite** `mvn -q test` — Expected: ALL PASS.
- [ ] **Step 4: Update TRACKER.md row for Task 10, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "test: concurrency stress for readers under concurrent config writes"
```

---

### Task 11: SLT suite

**Files:**
- Create: `src/test/java/com/example/flags/SltTest.java`

**Interfaces:**
- Consumes: Task 9 public API, Task 7 `ConfigStore`, Task 2 config records.
- Produces: `@Tag("slt")` tests excluded from default `mvn test`, run via `mvn verify -Pslt`. Thresholds are spec §6 CI-safe tripwires: p99 < 50µs, p999 < 1ms over 1M warmed evals (1 and 8 threads); propagation < 5s; throughput > 1M evals/sec/thread.

- [ ] **Step 1: Write the SLT tests**

```java
package com.example.flags;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FeatureFlagClient;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.*;
import com.example.flags.store.ConfigStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("slt")
class SltTest {

    private static ConfigStore storeWithRuleAndRollout() {
        var store = new ConfigStore();
        var rule = new Rule(List.of(new Clause("user.address.country", Operator.EQUALS, List.of("IN"))),
                null, new Rollout("user.id", null, 50, FlagValue.of(true)));
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, List.of(rule)));
        return store;
    }

    private static FeatureFlagClient client(ConfigStore store) {
        return FeatureFlagClient.builder().store(store).environment("prod").tenant("acme").build();
    }

    private static EvaluationContext ctx(int i) {
        return EvaluationContext.builder()
                .attribute("user", Map.of("id", "u-" + i, "address", Map.of("country", "IN")))
                .build();
    }

    @Test void evalLatencySingleThread() {
        var client = client(storeWithRuleAndRollout());
        var contexts = new EvaluationContext[1024];
        for (int i = 0; i < contexts.length; i++) contexts[i] = ctx(i);

        for (int i = 0; i < 200_000; i++) client.boolValue("f", contexts[i & 1023], false); // warmup

        int n = 1_000_000;
        long[] samples = new long[n / 100];                       // sample every 100th eval
        for (int i = 0, s = 0; i < n; i++) {
            if (i % 100 == 0) {
                long t0 = System.nanoTime();
                client.boolValue("f", contexts[i & 1023], false);
                samples[s++] = System.nanoTime() - t0;
            } else {
                client.boolValue("f", contexts[i & 1023], false);
            }
        }
        Arrays.sort(samples);
        long p99 = samples[(int) (samples.length * 0.99)];
        long p999 = samples[(int) (samples.length * 0.999)];
        assertTrue(p99 < 50_000, "p99=" + p99 + "ns (budget 50us)");
        assertTrue(p999 < 1_000_000, "p999=" + p999 + "ns (budget 1ms)");
    }

    @Test void throughputFloor() {
        var client = client(storeWithRuleAndRollout());
        var c = ctx(7);
        for (int i = 0; i < 200_000; i++) client.boolValue("f", c, false); // warmup
        int n = 2_000_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) client.boolValue("f", c, false);
        double perSec = n / ((System.nanoTime() - t0) / 1e9);
        assertTrue(perSec > 1_000_000, "throughput=" + (long) perSec + "/s (floor 1M/s)");
    }

    @Test void evalLatencyEightThreads() throws Exception {
        var client = client(storeWithRuleAndRollout());
        int threads = 8, perThread = 250_000;
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = pool.submit(() -> {
                    var c = ctx(ThreadLocalRandom.current().nextInt(1024));
                    for (int i = 0; i < perThread; i++) client.boolValue("f", c, false);
                });
            }
            long t0 = System.nanoTime();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            double totalPerSec = (double) threads * perThread / ((System.nanoTime() - t0) / 1e9);
            assertTrue(totalPerSec > 1_000_000, "8-thread aggregate=" + (long) totalPerSec + "/s");
        }
    }

    @Test void propagationUnderFiveSeconds() throws Exception {
        var store = new ConfigStore();
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, List.of()));
        var client = client(store);
        var c = EvaluationContext.builder().attribute("user", Map.of("id", "u-1")).build();
        assertFalse(client.boolValue("f", c, true));

        var observed = new CompletableFuture<Long>();
        Thread reader = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (client.boolValue("f", c, false)) { observed.complete(System.nanoTime()); return; }
            }
            observed.completeExceptionally(new AssertionError("update not observed within 5s"));
        });
        reader.start();
        long t0 = System.nanoTime();
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(true), true, null, List.of()));
        long seenAt = observed.get(6, TimeUnit.SECONDS);
        reader.join();
        long lagMillis = TimeUnit.NANOSECONDS.toMillis(seenAt - t0);
        assertTrue(lagMillis < 5_000, "propagation took " + lagMillis + "ms");
    }
}
```

- [ ] **Step 2: Verify exclusion** `mvn -q test` — Expected: PASS, SltTest NOT run (check `mvn test 2>&1 | grep -c SltTest` = 0).
- [ ] **Step 3: Run SLT** `mvn -q verify -Pslt` — Expected: PASS including SltTest. If a latency bound fails on this hardware, report the measured numbers; loosen ONLY with maintainer approval.
- [ ] **Step 4: Update TRACKER.md row for Task 11, commit**

```bash
git add src docs/superpowers/plans/TRACKER.md
git commit -m "test: SLT suite - latency, throughput, propagation tripwires"
```

---

### Task 12: README + final verify

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: everything (final gate).
- Produces: repo documentation; verified-green full build.

- [ ] **Step 1: Run everything** `mvn -q verify -Pslt` — Expected: ALL PASS. Fix nothing silently; failures get reported.
- [ ] **Step 2: Write README.md** covering: what it is (2 sentences); quickstart code block (build client, set config, evaluate — copy the shape from `FeatureFlagClientTest.happyPathAllTypes`); how to run tests (`mvn test`, `mvn verify -Pslt`); design decisions with one line each (immutable snapshot via AtomicReference → 0s propagation; pure-hash sticky bucketing → zero per-user state; salt = group ?: flagName → independent by default, nested cohorts when shared; tenant in hash input; rule-match terminality; two-layer error contract); trade-offs (O(flags) copy per write; SHA-256 vs murmur; no delete API — out of scope, add `delete(scopeKey)` when needed).
- [ ] **Step 3: Update TRACKER.md** — all rows done, add completion date.
- [ ] **Step 4: Commit**

```bash
git add README.md docs/superpowers/plans/TRACKER.md
git commit -m "docs: README with quickstart, design decisions, trade-offs"
```

---

## Self-Review Notes

- Spec coverage: flag types (T2/T9), targeting rules (T2/T6), rollouts + stickiness + sharing (T4/T8), env+tenant isolation (T7/T9), live updates <5s (T7/T11), never-throws + structured log (T5/T8/T9), store set/get (T7), tests incl. SLT (all, T10, T11). Nested-cohort semantics tested in T4 and T8. Rule terminality tested in T8.
- Type consistency: `FlagValue.of(...)` factories, `ConfigValidator.validate` returning normalized config, `AttributeResolver.resolve(Map, String)` — used identically across tasks.
- Known intentional cut: JSON parsing of config/context (spec: context IS nested maps; a JSON-fed resolver is a named extension point).
