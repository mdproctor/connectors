# Per-Tenant SLA Preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #375 — Multi-tenancy scoping: per-tenant SLA defaults YAML
**Issue group:** #375

**Goal:** Add per-tenant SLA breach action overrides via the platform's Preferences system, implemented as a CDI decorator on `SlaBreachPolicy`.

**Architecture:** A new `PreferenceSlaBreachPolicyDecorator` (CDI `@Decorator`, `APPLICATION + 200`) intercepts `SlaBreachPolicy.onBreach()` and checks tenant preferences before delegating to the underlying policy. Four new preference keys allow tenants to override breach actions and extension hours per scope. No new entities, migrations, or modules.

**Tech Stack:** Java 21, Quarkus 3.32.2, CDI decorators, casehub-platform-api Preferences

## Global Constraints

- All new classes go in `io.casehub.work.runtime.preferences` package
- Use `BreachAction.parseColon()` for preference serialization — same colon syntax as config properties
- `Preferences.get(key)` (not `getOrDefault`) to distinguish "not set" from "default"
- `ChainedAction` is excluded from preferences by design
- CDI priority ordering is ascending: lower value = outermost = called first
- Follow `CallbackSlaBreachPolicyDecorator` patterns for decorator structure, error handling, and test style

---

## Batch 1: Foundation — BreachActionPreference + Preference Keys

### Task 1: BreachActionPreference record and WorkPreferenceKeys additions

**Files:**
- Create: `runtime/src/main/java/io/casehub/work/runtime/preferences/BreachActionPreference.java`
- Modify: `runtime/src/main/java/io/casehub/work/runtime/preferences/WorkPreferenceKeys.java`
- Create: `runtime/src/test/java/io/casehub/work/runtime/preferences/BreachActionPreferenceTest.java`
- Create: `runtime/src/test/java/io/casehub/work/runtime/preferences/WorkPreferenceKeysTest.java`

**Interfaces:**
- Consumes: `BreachAction.parseColon(String)` from `io.casehub.work.runtime.service.BreachAction`, `SingleValuePreference` from platform-api
- Produces: `BreachActionPreference` record (used by Task 2's decorator), `WorkPreferenceKeys.SLA_ON_COMPLETION_EXPIRY`, `SLA_ON_CLAIM_EXPIRY`, `SLA_EXTENSION_HOURS`, `SLA_CLAIM_EXTENSION_HOURS` (used by Task 2)

- [ ] **Step 1: Write BreachActionPreference tests**

Create `runtime/src/test/java/io/casehub/work/runtime/preferences/BreachActionPreferenceTest.java`:

```java
package io.casehub.work.runtime.preferences;

import io.casehub.work.runtime.service.BreachAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreachActionPreferenceTest {

    @ParameterizedTest
    @CsvSource({
        "fail,          fail",
        "fail:custom,   fail:custom",
        "extend,        extend",
        "extend:PT6H,   extend:PT6H",
        "exhausted,     exhausted",
        "exhausted:why, exhausted:why",
        "escalateTo:group,          escalateTo:group",
        "escalateTo:group:PT4H,     escalateTo:group:PT4H"
    })
    void roundTrip(String input, String expected) {
        BreachActionPreference pref = BreachActionPreference.parse(input);
        assertThat(pref.toSerializedValue()).isEqualTo(expected);

        BreachActionPreference reparsed = BreachActionPreference.parse(pref.toSerializedValue());
        assertThat(reparsed.action()).isEqualTo(pref.action());
    }

    @Test
    void parseFail() {
        BreachActionPreference pref = BreachActionPreference.parse("fail");
        assertThat(pref.action()).isInstanceOf(BreachAction.FailAction.class);
        assertThat(((BreachAction.FailAction) pref.action()).reason()).isEqualTo("sla-breach");
    }

    @Test
    void parseEscalateToWithDeadline() {
        BreachActionPreference pref = BreachActionPreference.parse("escalateTo:team-leads:PT4H");
        assertThat(pref.action()).isInstanceOf(BreachAction.EscalateToAction.class);
        var esc = (BreachAction.EscalateToAction) pref.action();
        assertThat(esc.group()).isEqualTo("team-leads");
        assertThat(esc.deadline()).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void invalidStringThrows() {
        assertThatThrownBy(() -> BreachActionPreference.parse("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chainedActionSerializationThrows() {
        var chained = new BreachAction.ChainedAction(
                new BreachAction.FailAction("a"),
                new BreachAction.FailAction("b"));
        var pref = new BreachActionPreference(chained);
        assertThatThrownBy(pref::toSerializedValue)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ChainedAction");
    }

    @Test
    void unsetHasNullAction() {
        assertThat(BreachActionPreference.UNSET.action()).isNull();
        assertThat(BreachActionPreference.UNSET.toSerializedValue()).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=BreachActionPreferenceTest -pl runtime`
Expected: FAIL — class `BreachActionPreference` not found

- [ ] **Step 3: Implement BreachActionPreference**

Create `runtime/src/main/java/io/casehub/work/runtime/preferences/BreachActionPreference.java` using `ide_create_file`:

```java
package io.casehub.work.runtime.preferences;

import io.casehub.platform.api.preferences.SingleValuePreference;
import io.casehub.work.runtime.service.BreachAction;

public record BreachActionPreference(BreachAction action) implements SingleValuePreference {

    public static final BreachActionPreference UNSET = new BreachActionPreference(null);

    public static BreachActionPreference parse(String raw) {
        return new BreachActionPreference(BreachAction.parseColon(raw));
    }

    @Override
    public String toSerializedValue() {
        if (action == null) return "";
        return toColonString(action);
    }

    private static String toColonString(BreachAction action) {
        return switch (action) {
            case BreachAction.FailAction f ->
                "sla-breach".equals(f.reason()) ? "fail" : "fail:" + f.reason();
            case BreachAction.ExtendAction e ->
                e.explicitDuration() == null ? "extend" : "extend:" + e.explicitDuration();
            case BreachAction.EscalateToAction e ->
                e.deadline() == null
                    ? "escalateTo:" + e.group()
                    : "escalateTo:" + e.group() + ":" + e.deadline();
            case BreachAction.ExhaustedAction e ->
                "sla-exhausted".equals(e.reason()) ? "exhausted" : "exhausted:" + e.reason();
            case BreachAction.ChainedAction c ->
                throw new UnsupportedOperationException(
                    "ChainedAction cannot be serialized as a preference — use CallbackSlaBreachPolicyDecorator for complex fallback chains");
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=BreachActionPreferenceTest -pl runtime`
Expected: PASS — all tests green

- [ ] **Step 5: Write WorkPreferenceKeys tests**

Create `runtime/src/test/java/io/casehub/work/runtime/preferences/WorkPreferenceKeysTest.java`:

```java
package io.casehub.work.runtime.preferences;

import io.casehub.platform.api.preferences.IntPreference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkPreferenceKeysTest {

    @Test
    void slaOnCompletionExpiryKey() {
        assertThat(WorkPreferenceKeys.SLA_ON_COMPLETION_EXPIRY.qualifiedName())
                .isEqualTo("casehub.work.sla.on-completion-expiry");
        var parsed = WorkPreferenceKeys.SLA_ON_COMPLETION_EXPIRY.parse("fail");
        assertThat(parsed.action()).isNotNull();
    }

    @Test
    void slaOnClaimExpiryKey() {
        assertThat(WorkPreferenceKeys.SLA_ON_CLAIM_EXPIRY.qualifiedName())
                .isEqualTo("casehub.work.sla.on-claim-expiry");
        var parsed = WorkPreferenceKeys.SLA_ON_CLAIM_EXPIRY.parse("extend:PT2H");
        assertThat(parsed.action()).isNotNull();
    }

    @Test
    void slaExtensionHoursKey() {
        assertThat(WorkPreferenceKeys.SLA_EXTENSION_HOURS.qualifiedName())
                .isEqualTo("casehub.work.sla.extension-hours");
        IntPreference parsed = WorkPreferenceKeys.SLA_EXTENSION_HOURS.parse("8");
        assertThat(parsed.value()).isEqualTo(8);
    }

    @Test
    void slaClaimExtensionHoursKey() {
        assertThat(WorkPreferenceKeys.SLA_CLAIM_EXTENSION_HOURS.qualifiedName())
                .isEqualTo("casehub.work.sla.claim-extension-hours");
        IntPreference parsed = WorkPreferenceKeys.SLA_CLAIM_EXTENSION_HOURS.parse("12");
        assertThat(parsed.value()).isEqualTo(12);
    }

    @Test
    void defaultsAreNonNull() {
        assertThat(WorkPreferenceKeys.SLA_ON_COMPLETION_EXPIRY.defaultValue()).isNotNull();
        assertThat(WorkPreferenceKeys.SLA_ON_CLAIM_EXPIRY.defaultValue()).isNotNull();
        assertThat(WorkPreferenceKeys.SLA_EXTENSION_HOURS.defaultValue()).isNotNull();
        assertThat(WorkPreferenceKeys.SLA_CLAIM_EXTENSION_HOURS.defaultValue()).isNotNull();
    }
}
```

- [ ] **Step 6: Add preference keys to WorkPreferenceKeys**

Use `ide_insert_member` to add the four new keys to `WorkPreferenceKeys.java` after the existing `DEFAULT_CLAIM_HOURS` field:

```java
public static final PreferenceKey<BreachActionPreference> SLA_ON_COMPLETION_EXPIRY =
        new PreferenceKey<>("casehub.work", "sla.on-completion-expiry",
                BreachActionPreference.UNSET, BreachActionPreference::parse);

public static final PreferenceKey<BreachActionPreference> SLA_ON_CLAIM_EXPIRY =
        new PreferenceKey<>("casehub.work", "sla.on-claim-expiry",
                BreachActionPreference.UNSET, BreachActionPreference::parse);

public static final PreferenceKey<IntPreference> SLA_EXTENSION_HOURS =
        new PreferenceKey<>("casehub.work", "sla.extension-hours",
                IntPreference.of(0), IntPreference::parse);

public static final PreferenceKey<IntPreference> SLA_CLAIM_EXTENSION_HOURS =
        new PreferenceKey<>("casehub.work", "sla.claim-extension-hours",
                IntPreference.of(0), IntPreference::parse);
```

Add import: `import io.casehub.work.runtime.preferences.BreachActionPreference;`

- [ ] **Step 7: Run both test classes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest="BreachActionPreferenceTest,WorkPreferenceKeysTest" -pl runtime`
Expected: PASS

- [ ] **Step 8: Run existing tests to verify no regressions**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest="BreachActionTest,DeclarativeSlaBreachPolicyTest,CallbackSlaBreachPolicyDecoratorTest" -pl runtime`
Expected: PASS — no regressions

- [ ] **Step 9: Verify with ide_diagnostics**

Run `ide_diagnostics` on both new files and the modified `WorkPreferenceKeys.java`.

- [ ] **Step 10: Commit**

```bash
git add runtime/src/main/java/io/casehub/work/runtime/preferences/BreachActionPreference.java \
        runtime/src/main/java/io/casehub/work/runtime/preferences/WorkPreferenceKeys.java \
        runtime/src/test/java/io/casehub/work/runtime/preferences/BreachActionPreferenceTest.java \
        runtime/src/test/java/io/casehub/work/runtime/preferences/WorkPreferenceKeysTest.java
git commit -m "feat(#375): add BreachActionPreference and SLA preference keys

BreachActionPreference wraps BreachAction with colon-delimited
serialization for the Preferences system. Four new keys in
WorkPreferenceKeys for per-tenant SLA overrides.

Refs #375"
```

---

## Batch 2: Decorator — PreferenceSlaBreachPolicyDecorator

### Task 2: PreferenceSlaBreachPolicyDecorator and unit tests

**Files:**
- Create: `runtime/src/main/java/io/casehub/work/runtime/preferences/PreferenceSlaBreachPolicyDecorator.java`
- Create: `runtime/src/test/java/io/casehub/work/runtime/preferences/PreferenceSlaBreachPolicyDecoratorTest.java`

**Interfaces:**
- Consumes: `BreachActionPreference` and all `WorkPreferenceKeys.SLA_*` keys from Task 1, `SlaBreachPolicy` SPI, `SlaBreachContext.preferences()`, `WorkItemsConfig.defaultExpiryHours()`, `MapPreferences` for test construction
- Produces: CDI decorator that intercepts `SlaBreachPolicy.onBreach()` — no downstream consumers in this plan

- [ ] **Step 1: Write decorator tests**

Create `runtime/src/test/java/io/casehub/work/runtime/preferences/PreferenceSlaBreachPolicyDecoratorTest.java`:

```java
package io.casehub.work.runtime.preferences;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.IntPreference;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.work.api.BreachDecision;
import io.casehub.work.api.BreachType;
import io.casehub.work.api.BreachedTask;
import io.casehub.work.api.SlaBreachContext;
import io.casehub.work.api.spi.SlaBreachPolicy;
import io.casehub.work.runtime.config.WorkItemsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferenceSlaBreachPolicyDecoratorTest {

    private SlaBreachPolicy delegate;
    private WorkItemsConfig config;
    private PreferenceSlaBreachPolicyDecorator decorator;

    @BeforeEach
    void setUp() {
        delegate = mock(SlaBreachPolicy.class);
        when(delegate.id()).thenReturn("declarative");

        config = mock(WorkItemsConfig.class);
        when(config.defaultExpiryHours()).thenReturn(24);

        decorator = new PreferenceSlaBreachPolicyDecorator(delegate, config);
    }

    @Test
    void id_alwaysDelegates() {
        assertThat(decorator.id()).isEqualTo("declarative");
    }

    @Test
    void noPreference_delegates() {
        var ctx = ctx(BreachType.COMPLETION_EXPIRED, Map.of(), Set.of("ops"));
        var expected = new BreachDecision.Fail("fallback");
        when(delegate.onBreach(any())).thenReturn(expected);

        assertThat(decorator.onBreach(ctx)).isSameAs(expected);
    }

    @Test
    void completionPreference_returnsWithoutDelegating() {
        var ctx = ctx(BreachType.COMPLETION_EXPIRED,
                Map.of("casehub.work.sla.on-completion-expiry", "fail:tenant-reason"),
                Set.of("ops"));

        var result = decorator.onBreach(ctx);

        assertThat(result).isInstanceOf(BreachDecision.Fail.class);
        assertThat(((BreachDecision.Fail) result).reason()).isEqualTo("tenant-reason");
        verify(delegate, never()).onBreach(any());
    }

    @Test
    void claimPreference_routesToCorrectKey() {
        var ctx = ctx(BreachType.CLAIM_EXPIRED,
                Map.of("casehub.work.sla.on-claim-expiry", "exhausted:claim-timeout"),
                Set.of("ops"));

        var result = decorator.onBreach(ctx);

        assertThat(result).isInstanceOf(BreachDecision.Exhausted.class);
        verify(delegate, never()).onBreach(any());
    }

    @Test
    void completionPreference_doesNotMatchClaimExpiry() {
        var ctx = ctx(BreachType.CLAIM_EXPIRED,
                Map.of("casehub.work.sla.on-completion-expiry", "fail"),
                Set.of("ops"));
        var expected = new BreachDecision.Fail("fallback");
        when(delegate.onBreach(any())).thenReturn(expected);

        assertThat(decorator.onBreach(ctx)).isSameAs(expected);
    }

    @Test
    void extendAction_usesExtensionHoursPreference() {
        var ctx = ctx(BreachType.COMPLETION_EXPIRED,
                Map.of("casehub.work.sla.on-completion-expiry", "extend",
                       "casehub.work.sla.extension-hours", "8"),
                Set.of("ops"));

        var result = decorator.onBreach(ctx);

        assertThat(result).isInstanceOf(BreachDecision.Extend.class);
        assertThat(((BreachDecision.Extend) result).by()).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void extendAction_fallsBackToConfigDefaultExpiryHours() {
        when(config.defaultExpiryHours()).thenReturn(12);
        var ctx = ctx(BreachType.COMPLETION_EXPIRED,
                Map.of("casehub.work.sla.on-completion-expiry", "extend"),
                Set.of("ops"));

        var result = decorator.onBreach(ctx);

        assertThat(result).isInstanceOf(BreachDecision.Extend.class);
        assertThat(((BreachDecision.Extend) result).by()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void claimExtensionHours_takesPrecedenceOverExtensionHours() {
        var ctx = ctx(BreachType.CLAIM_EXPIRED,
                Map.of("casehub.work.sla.on-claim-expiry", "extend",
                       "casehub.work.sla.extension-hours", "4",
                       "casehub.work.sla.claim-extension-hours", "8"),
                Set.of("ops"));

        var result = decorator.onBreach(ctx);

        assertThat(result).isInstanceOf(BreachDecision.Extend.class);
        assertThat(((BreachDecision.Extend) result).by()).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void selfEscalation_delegates() {
        var ctx = ctx(BreachType.COMPLETION_EXPIRED,
                Map.of("casehub.work.sla.on-completion-expiry", "escalateTo:team-leads"),
                Set.of("team-leads"));
        var expected = new BreachDecision.Fail("fallback");
        when(delegate.onBreach(any())).thenReturn(expected);

        assertThat(decorator.onBreach(ctx)).isSameAs(expected);
    }

    @Test
    void escalateToNewGroup_works() {
        var ctx = ctx(BreachType.COMPLETION_EXPIRED,
                Map.of("casehub.work.sla.on-completion-expiry", "escalateTo:managers:PT2H"),
                Set.of("team-leads"));

        var result = decorator.onBreach(ctx);

        assertThat(result).isInstanceOf(BreachDecision.EscalateTo.class);
        var esc = (BreachDecision.EscalateTo) result;
        assertThat(esc.groups()).containsExactly("managers");
        assertThat(esc.deadline()).isEqualTo(Duration.ofHours(2));
        verify(delegate, never()).onBreach(any());
    }

    @Test
    void malformedPreference_logsAndDelegates() {
        var ctx = ctx(BreachType.COMPLETION_EXPIRED,
                Map.of("casehub.work.sla.on-completion-expiry", "invalid-action"),
                Set.of("ops"));
        var expected = new BreachDecision.Fail("fallback");
        when(delegate.onBreach(any())).thenReturn(expected);

        assertThat(decorator.onBreach(ctx)).isSameAs(expected);
    }

    @Test
    void worksWithNonDeclarativeDelegate() {
        SlaBreachPolicy customDelegate = mock(SlaBreachPolicy.class);
        when(customDelegate.id()).thenReturn("custom-policy");
        when(customDelegate.onBreach(any())).thenReturn(new BreachDecision.Fail("custom"));
        var customDecorator = new PreferenceSlaBreachPolicyDecorator(customDelegate, config);

        assertThat(customDecorator.id()).isEqualTo("custom-policy");
        var ctx = ctx(BreachType.COMPLETION_EXPIRED, Map.of(), Set.of("ops"));
        assertThat(customDecorator.onBreach(ctx)).isInstanceOf(BreachDecision.Fail.class);
    }

    private static SlaBreachContext ctx(BreachType type, Map<String, Object> prefValues,
                                         Set<String> candidateGroups) {
        var task = new BreachedTask(UUID.randomUUID(), null, "test-item", candidateGroups);
        return new SlaBreachContext(type, task, Path.parse("casehubio/clinical"),
                new MapPreferences(prefValues));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=PreferenceSlaBreachPolicyDecoratorTest -pl runtime`
Expected: FAIL — class `PreferenceSlaBreachPolicyDecorator` not found

- [ ] **Step 3: Implement the decorator**

Create `runtime/src/main/java/io/casehub/work/runtime/preferences/PreferenceSlaBreachPolicyDecorator.java` using `ide_create_file`:

```java
package io.casehub.work.runtime.preferences;

import io.casehub.platform.api.preferences.IntPreference;
import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.work.api.BreachDecision;
import io.casehub.work.api.BreachType;
import io.casehub.work.api.SlaBreachContext;
import io.casehub.work.api.spi.SlaBreachPolicy;
import io.casehub.work.runtime.config.WorkItemsConfig;
import io.casehub.work.runtime.service.BreachAction;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@Decorator
@Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION + 200)
public class PreferenceSlaBreachPolicyDecorator implements SlaBreachPolicy {

    private static final Logger LOG = Logger.getLogger(PreferenceSlaBreachPolicyDecorator.class);

    private final SlaBreachPolicy delegate;
    private final WorkItemsConfig config;

    @Inject
    PreferenceSlaBreachPolicyDecorator(@Delegate final SlaBreachPolicy delegate,
                                       final WorkItemsConfig config) {
        this.delegate = delegate;
        this.config = config;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public BreachDecision onBreach(final SlaBreachContext context) {
        final Preferences prefs = context.preferences();

        final PreferenceKey<BreachActionPreference> actionKey = switch (context.breachType()) {
            case COMPLETION_EXPIRED -> WorkPreferenceKeys.SLA_ON_COMPLETION_EXPIRY;
            case CLAIM_EXPIRED -> WorkPreferenceKeys.SLA_ON_CLAIM_EXPIRY;
        };

        final BreachActionPreference actionPref;
        try {
            actionPref = prefs.get(actionKey);
        } catch (final Exception e) {
            LOG.warnf(e, "Failed to parse SLA preference for %s — falling back to policy",
                      actionKey.qualifiedName());
            return delegate.onBreach(context);
        }
        if (actionPref == null) {
            return delegate.onBreach(context);
        }

        if (actionPref.action() instanceof BreachAction.EscalateToAction esc
                && context.task().candidateGroups().contains(esc.group())) {
            return delegate.onBreach(context);
        }

        final Integer extensionHours = resolveExtensionHours(prefs, context.breachType());
        return actionPref.action().toBreachDecision(extensionHours, config.defaultExpiryHours());
    }

    private Integer resolveExtensionHours(Preferences prefs, BreachType type) {
        if (type == BreachType.CLAIM_EXPIRED) {
            IntPreference claimExt = prefs.get(WorkPreferenceKeys.SLA_CLAIM_EXTENSION_HOURS);
            if (claimExt != null) return claimExt.value();
        }
        IntPreference ext = prefs.get(WorkPreferenceKeys.SLA_EXTENSION_HOURS);
        return ext != null ? ext.value() : null;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=PreferenceSlaBreachPolicyDecoratorTest -pl runtime`
Expected: PASS — all tests green

- [ ] **Step 5: Run full runtime test suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl runtime` (use scripts/ timeout wrapper if available)
Expected: PASS — no regressions in existing tests

- [ ] **Step 6: Verify with ide_diagnostics**

Run `ide_diagnostics` on `PreferenceSlaBreachPolicyDecorator.java` and the test class.

- [ ] **Step 7: Run integration tests to verify decorator doesn't break existing SLA wiring**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn verify -pl integration-tests`
Expected: PASS — existing integration tests pass with the new decorator in the CDI chain (passthrough when no preferences set)

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/io/casehub/work/runtime/preferences/PreferenceSlaBreachPolicyDecorator.java \
        runtime/src/test/java/io/casehub/work/runtime/preferences/PreferenceSlaBreachPolicyDecoratorTest.java
git commit -m "feat(#375): add PreferenceSlaBreachPolicyDecorator for per-tenant SLA overrides

CDI decorator at APPLICATION+200 checks tenant preferences before
delegating to the underlying SLA breach policy. Includes self-escalation
guard, parse failure resilience, and config.defaultExpiryHours() fallback.

Closes #375"
```

---

## References

- [2026-09-01-per-tenant-sla-preferences-design.md] — design spec this plan implements
- [decisions.md D1-D6] — reviewed design decisions
- `runtime/src/main/java/io/casehub/work/runtime/service/BreachAction.java:79` — `parseColon()` reused
- `runtime/src/main/java/io/casehub/work/runtime/preferences/WorkPreferenceKeys.java` — existing keys
- `runtime/src/main/java/io/casehub/work/runtime/callback/CallbackSlaBreachPolicyDecorator.java` — pattern
- `runtime/src/test/java/io/casehub/work/runtime/callback/CallbackSlaBreachPolicyDecoratorTest.java` — test pattern
- `runtime/src/main/java/io/casehub/work/runtime/service/DeclarativeSlaBreachPolicy.java` — unchanged
- `runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java:318` — `buildBreachContext()`
- Platform: `MapPreferences.class`, `PreferenceKey.class`, `SingleValuePreference.class`
- Protocol: async-event-tenant-context-propagation (PP-20260609-fb6563)
- GitHub casehubio/work#375
