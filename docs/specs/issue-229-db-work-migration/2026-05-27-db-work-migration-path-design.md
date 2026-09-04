# Design: Repo-scoped Flyway migration path for casehub-work

**Issue:** casehubio/work#229  
**Branch:** issue-229-db-work-migration  
**Date:** 2026-05-27  
**Protocol:** PP-20260525-607b33 (flyway-repo-scoped-migration-path)

---

## Problem

All casehub-work modules currently ship Flyway migrations at `classpath:db/migration`. Quarkus Flyway scans `classpath:db/migration` across ALL JARs on the classpath (GE-20260511-a28064). When a consumer embeds casehub-work alongside casehub-qhorus or casehub-engine-persistence-hibernate — both of which also use `classpath:db/migration` — Flyway startup fails with "Found more than one migration with version 1".

A secondary gotcha (GE-20260521-effd2f): the `db/migration/<module>/` subdirectory pattern does not solve this — Flyway's recursive scan of the parent path still discovers subdirectory files.

The platform protocol PP-20260525-607b33 mandates `db/<reponame>/migration/` as the scoped path. casehub-work's target path is `classpath:db/work/migration`. This issue implements the rename and makes the extension self-register its migration path for JVM-mode consumers. Native image requires additional build-time configuration (see Section 2).

---

## Design

### 1. File structure

Every casehub-work module moves its migrations from `src/main/resources/db/migration/` to `src/main/resources/db/work/migration/`. No version numbers change — only the directory.

**Classpath migrations:**

| Module | Migrations |
|---|---|
| `runtime` | V1–V31 (core WorkItem schema; V14 deliberately absent — occupied by ai module) |
| `ai` | V14 (worker_skill_profile), V4001 (escalation_summary) |
| `ledger` | V2001 (work_item_ledger_entry) |
| `notifications` | V3000 (notification_rules) |
| `queues` | V2000 (queues_schema), V2002 (queue_membership_tracking) |
| `issue-tracker` | V5000 (issue_link_schema), V5001 (priority_rename) |

**V14 ordering note:** V14 is a deliberate gap in the runtime module, occupied by the ai optional module. This pre-existing constraint is unchanged by the rename — all files remain in the same single namespace (`db/work/migration/`), and Flyway applies them in version order regardless. Consumers using runtime without ai see V1–V13, V15–V31 with no gap problem (Flyway tolerates missing versions). Consumers who add ai after initial deployment will encounter `V14 is below current version` if `outOfOrder=false` (Flyway default). This constraint predates this issue and is documented in `docs/FLYWAY.md`.

**Other directory changes:**
- `core/src/main/resources/db/migration/` (empty directory, no SQL files) — deleted.
- Root-level `db/migration/` (DBA reference scripts: V1 and V1000–V1004) — renamed to `db/work/migration/` at project root. These files are **not on any classpath** — they exist as DBA reference copies for manual schema review. Moving them to `db/work/migration/` keeps the naming consistent with the classpath path. A comment in that directory explains they are reference-only.

### 2. Extension self-registration

The extension registers its migration path so JVM-mode consumers never need to configure `quarkus.flyway.locations` manually.

#### 2a. Deployment module — `WorkItemsProcessor`

New `@BuildStep` for native image resource registration:

```java
@BuildStep
NativeImageResourcePatternsBuildItem registerMigrationResources() {
    return new NativeImageResourcePatternsBuildItem("db/work/migration/.*\\.sql");
}
```

This makes SQL bytes available inside the native binary. It does NOT pre-register files in Quarkus's native Flyway file registry (see the native image limitation below).

#### 2b. Runtime module — new `WorkItemsMigrationCustomizer`

New `@ApplicationScoped` CDI bean implementing `io.quarkus.flyway.FlywayConfigurationCustomizer` (confirmed class name from `quarkus-flyway-3.32.2.jar`):

```java
@ApplicationScoped
public class WorkItemsMigrationCustomizer implements FlywayConfigurationCustomizer {

    @Override
    public void customize(FluentConfiguration configuration) {
        Location target = new Location("classpath:db/work/migration");
        List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
        // Location.getPath() strips the classpath: prefix — comparison is prefix-agnostic
        if (locations.stream().noneMatch(l -> l.getPath().equals(target.getPath()))) {
            locations.add(target);
            configuration.locations(locations.toArray(new Location[0]));
        }
    }
}
```

**Multi-datasource behaviour (confirmed from `FlywayContainerProducer` bytecode):** An unqualified `FlywayConfigurationCustomizer` bean (no `@FlywayDataSource` annotation) is applied only to the **default datasource**, not to named datasources. `matchingConfigCustomizers()` routes unqualified beans via `DataSourceUtil.isDefault(datasourceName)` at lines 175–184 of the compiled class. casehub-work uses the default datasource; consumers with named datasources (e.g., `qhorus`) are unaffected. No `@FlywayDataSource` qualifier is needed.

The customizer is discovered automatically — it is a first-class `@ApplicationScoped` CDI bean in the extension runtime module, which is always Jandex-indexed as part of the Quarkus extension contract. No `@BuildStep AdditionalBeans` registration is needed.

#### 2c. Native image limitation

Quarkus's `FlywayProcessor.build()` pre-registers migration file lists at **build time** by scanning `classpath:db/work/migration` at the configured `quarkus.flyway.locations`. The `FlywayConfigurationCustomizer` runs at runtime — after the native image's file registry is already frozen. At native runtime, `QuarkusPathLocationScanner` / `QuarkusFlywayResourceProvider` serve migrations from the pre-registered list only.

**Consequence:** In JVM mode, the customizer is fully automatic. In native image mode, consumers must explicitly add `classpath:db/work/migration` to their build-time Flyway configuration:

```properties
# Required for native image only — JVM mode is automatic via WorkItemsMigrationCustomizer
quarkus.flyway.locations=classpath:db/work/migration
```

The `NativeImageResourcePatternsBuildItem` in `WorkItemsProcessor` ensures the SQL bytes are present in the native binary (a prerequisite for this to work at all, but insufficient on its own).

This split behaviour (JVM automatic, native manual) is documented in `docs/FLYWAY.md`, `docs/GOTCHAS.md`, and the new protocol.

### 3. application.properties cleanup

With the customizer in place, JVM-mode test properties no longer need to declare `db/work/migration`. Modules relying on Quarkus's default `classpath:db/migration` location receive the work migrations automatically (the customizer adds `db/work/migration` to whatever locations are configured). The default `db/migration` scan finds nothing from casehub-work — this is harmless because `flyway.fail-on-missing-locations` defaults to false and no test properties in this project set it.

**Files with explicit changes:**

| File | Before | After |
|---|---|---|
| `notifications/src/test/resources/application.properties` | `quarkus.flyway.locations=db/migration` | remove line |
| `queues/src/test/resources/application.properties` | `quarkus.flyway.locations=db/migration` | remove line |
| `queues-dashboard/src/main/resources/application.properties` | `quarkus.flyway.locations=db/migration` | remove line |
| `examples/src/test/resources/application.properties` | `db/migration,db/ledger/migration` | `db/ledger/migration` only |
| `ledger/src/test/resources/application.properties` | `db/migration,db/ledger/migration` | `db/ledger/migration` only |
| `integration-tests/src/main/resources/application.properties` | (no locations set) | add `quarkus.flyway.locations=classpath:db/work/migration` (unconditional — see Section 4) |

**Files confirmed as no change needed:**

| File | Reason |
|---|---|
| `runtime/src/test` | No explicit locations — customizer adds work path to Quarkus default |
| `ai/src/test` | No explicit locations — customizer handles |
| `core/src/test` | No migrations in core; runtime is not a test dependency of core, so the customizer is not on the test classpath — Flyway scans the default `classpath:db/migration` which is now empty (harmless) |
| `flow/src/test` | No explicit locations — customizer handles |
| `reports/src/test` | No explicit locations — customizer handles |
| `issue-tracker/src/test` | No explicit locations — customizer handles |
| ~~`integration-tests/src/main`~~ | Moved to explicit changes table — requires `quarkus.flyway.locations` for native image |
| `persistence-mongodb/src/test` | No explicit locations — customizer handles |
| `queues-examples/src/main` | No SQL migrations; has `quarkus.flyway.migrate-at-start=true` — customizer adds path, Flyway finds nothing (harmless) |
| `flow-examples/src/main` | No SQL migrations; same as queues-examples |
| `examples/src/main` | No explicit locations — customizer handles; ledger config in this file is about `casehub.ledger.*`, not Flyway locations |

The `db/ledger/migration` entries in `examples` and `ledger` test properties remain until casehub-ledger adopts the same pattern (tracked separately).

### 4. Testing

**Unit test — `WorkItemsMigrationCustomizerTest`** in the runtime module:
- Given a `FluentConfiguration` with no locations: customizer adds `classpath:db/work/migration`.
- Given a `FluentConfiguration` already containing `classpath:db/work/migration`: customizer is idempotent (no duplicate added).
- Given a `FluentConfiguration` with `classpath:db/ledger/migration`: customizer adds work path, ledger path unchanged.

**Integration smoke test** — existing `@QuarkusTest` suites in `runtime/src/test` verify schema correctness after migration. These pass automatically if the customizer correctly registers `db/work/migration`. No new test class needed — migration failures manifest as `Table not found` errors in existing tests.

**Native image:** `@QuarkusIntegrationTest` in `integration-tests/` validates native image behaviour. `integration-tests/src/main/resources/application.properties` must add `quarkus.flyway.locations=classpath:db/work/migration` unconditionally (Option B — not `%native`-prefixed). Rationale: both JVM and native integration tests use explicit config, which is simpler and exercises the idempotency guard as a side effect (in JVM mode the customizer also runs and the guard prevents a duplicate). This is the explicit native verification gate.

### 5. Protocol and PLATFORM.md

**New protocol** `flyway-extension-migration-registration.md` written to `casehub-parent/docs/protocols/universal/`. Scope: universal — applies to any Quarkus extension in the casehubio ecosystem.

Rule: Quarkus extensions that ship Flyway migrations must:
1. Use a repo-scoped migration path `db/<repo>/migration/` (governed by PP-20260525-607b33)
2. Implement `FlywayConfigurationCustomizer` as an `@ApplicationScoped` CDI bean in the extension's runtime module to self-register that path — additive and idempotent
3. Register SQL resources for native image via `NativeImageResourcePatternsBuildItem` in the deployment module
4. Document the native image limitation: `FlywayConfigurationCustomizer` runs at JVM startup only; native image consumers must add the migration path to build-time `quarkus.flyway.locations`

**PLATFORM.md** Implementation Protocols table gains a row for this protocol, adjacent to the existing Flyway migration rules row.

**casehub-ledger issue** filed to implement `FlywayConfigurationCustomizer` for `classpath:db/ledger/migration`. Once merged, the `db/ledger/migration` entries in casehub-work test properties can be removed.

### 6. Documentation updates

- `docs/FLYWAY.md`: Update location references from `db/migration` → `db/work/migration`. Add section on `WorkItemsMigrationCustomizer` and the JVM-vs-native split. Update casehub-ledger prerequisites section to note that ledger adoption is pending.
- `docs/GOTCHAS.md`: Update the classpath collision gotcha to reference the resolution. Add entry for native image Flyway location limitation.
- `docs/DESIGN.md`: Record the migration path change and new protocol in the relevant sections.

---

## What is not in scope

- Renaming existing flyway_schema_history records in live databases (platform has no external users; fresh installs only; `flyway_schema_history` stores version numbers and checksums, not file paths — schema history records are unaffected)
- casehub-ledger adopting `FlywayConfigurationCustomizer` (separate issue, casehub-ledger repo)
- Removing `db/ledger/migration` entries from test properties (blocked on the ledger issue)
- Any V-number changes

---

## Consumer impact summary

| Mode | Requirement |
|---|---|
| JVM — default datasource | Fully automatic. `WorkItemsMigrationCustomizer` adds `db/work/migration` to Flyway locations. No consumer config change needed. |
| JVM — named datasources | Unaffected. Customizer applies to default datasource only (confirmed from Quarkus bytecode). |
| Native image | Consumer must add `quarkus.flyway.locations=classpath:db/work/migration` to build-time config. `NativeImageResourcePatternsBuildItem` ensures SQL bytes are in the binary. |

Consumers currently declaring `quarkus.flyway.locations=classpath:db/migration` will receive both `db/migration` (empty — harmless) and `db/work/migration` (via customizer). They can clean up the `db/migration` entry once they confirm no other library uses it.
