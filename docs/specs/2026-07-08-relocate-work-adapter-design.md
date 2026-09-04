# Relocate HumanTask Adapter from casehub-engine to casehub-work

**Issue:** casehubio/work#290
**Date:** 2026-07-08
**Status:** Approved

## Problem

`casehub-engine-work-adapter` lives in the engine repo but is a Work domain concept — it creates and manages WorkItems on behalf of the engine. The module has no dependents in engine; it's a leaf module consumed as an optional runtime dependency. Ownership belongs with work.

Secondary issues:
- `WorkItemCallerRef.parseCaseId()` in work-api is broken — it expects `{caseId}:{planItemId}` format but the adapter actually encodes `case:{caseId}/pi:{planItemId}`. Returns `null` for every real engine callerRef.
- CLAUDE.md incorrectly claims `casehub-engine-actor-state` uses `WorkItemCallerRef` — verified false.

## Approach

Three phases, in order. Each phase is independently verifiable.

### Phase 1 — Package Rename (in engine, via IntelliJ)

Rename `io.casehub.workadapter` → `io.casehub.work.engine` while all code is in one project. IntelliJ's rename refactoring updates all internal references atomically — more reliable than renaming after a cross-repo move.

**Files affected:**

Source (12 files in `io.casehub.workadapter`):
| File | New package |
|------|------------|
| `CallerRef.java` | `io.casehub.work.engine` |
| `PlanItemCallerRef.java` | `io.casehub.work.engine` |
| `GateCallerRef.java` | `io.casehub.work.engine` |
| `HumanTaskScheduleHandler.java` | `io.casehub.work.engine` |
| `WorkItemLifecycleAdapter.java` | `io.casehub.work.engine` |
| `PlanItemCompletionApplier.java` | `io.casehub.work.engine` |
| `ActionGateCompletionApplier.java` | `io.casehub.work.engine` |
| `ActionGateWorkItemHandler.java` | `io.casehub.work.engine` |
| `ActionGateCancelledHandler.java` | `io.casehub.work.engine` |
| `JpaPlanItemStore.java` | `io.casehub.work.engine` |
| `WorkAdapterPlanItemEntity.java` | `io.casehub.work.engine` |
| `recovery/HumanTaskRecoveryService.java` | `io.casehub.work.engine.recovery` |

Test (10 files in `io.casehub.workadapter`):
| File | New package |
|------|------------|
| `HumanTaskScheduleHandlerTest.java` | `io.casehub.work.engine` |
| `HumanTaskScheduleHandlerAtomicityTest.java` | `io.casehub.work.engine` |
| `HumanTaskPlannerIntegrationTest.java` | `io.casehub.work.engine` |
| `WorkItemLifecycleAdapterTest.java` | `io.casehub.work.engine` |
| `CallerRefTest.java` | `io.casehub.work.engine` |
| `JpaPlanItemStoreTest.java` | `io.casehub.work.engine` |
| `ActionGateHandlerTest.java` | `io.casehub.work.engine` |
| `NoOpPreferenceProvider.java` | `io.casehub.work.engine` |
| `NoOpCurrentPrincipal.java` | `io.casehub.work.engine` |
| `recovery/HumanTaskRecoveryServiceTest.java` | `io.casehub.work.engine.recovery` |

Config reference:
- `application.properties`: `io.casehub.workadapter.NoOpPreferenceProvider` → `io.casehub.work.engine.NoOpPreferenceProvider`

**Cross-module reference:** `ActionGateDeploymentHealthCheck` in engine runtime uses `Class.forName("io.casehub.workadapter.ActionGateWorkItemHandler", ...)` to detect work-adapter presence. Update to `io.casehub.work.engine.ActionGateWorkItemHandler`.

**Verification:** `mvn test -pl work-adapter` passes in engine. Additionally, grep engine for any remaining `io.casehub.workadapter` references (catches cross-module string literals like `ActionGateDeploymentHealthCheck`).

**No dependents:** Nothing in engine or any other casehub repo imports from `io.casehub.workadapter`. Verified via IntelliJ find-references and grep. The sole cross-module reference (`ActionGateDeploymentHealthCheck.Class.forName`) is a string literal, not an import — covered by the grep verification above.

### Phase 2 — Module Relocation (engine → work)

Create `engine-adapter/` module in casehub-work with artifactId `casehub-work-engine-adapter`.

#### 2a. Create the module in work

**Directory structure:**
```
work/engine-adapter/
├── pom.xml
└── src/
    ├── main/java/io/casehub/work/engine/
    │   ├── CallerRef.java
    │   ├── PlanItemCallerRef.java
    │   ├── GateCallerRef.java
    │   ├── HumanTaskScheduleHandler.java
    │   ├── WorkItemLifecycleAdapter.java
    │   ├── PlanItemCompletionApplier.java
    │   ├── ActionGateCompletionApplier.java
    │   ├── ActionGateWorkItemHandler.java
    │   ├── ActionGateCancelledHandler.java
    │   ├── JpaPlanItemStore.java
    │   ├── WorkAdapterPlanItemEntity.java
    │   └── recovery/
    │       └── HumanTaskRecoveryService.java
    └── test/
        ├── java/io/casehub/work/engine/
        │   ├── HumanTaskScheduleHandlerTest.java
        │   ├── HumanTaskScheduleHandlerAtomicityTest.java
        │   ├── HumanTaskPlannerIntegrationTest.java
        │   ├── WorkItemLifecycleAdapterTest.java
        │   ├── CallerRefTest.java
        │   ├── JpaPlanItemStoreTest.java
        │   ├── ActionGateHandlerTest.java
        │   ├── NoOpPreferenceProvider.java
        │   ├── NoOpCurrentPrincipal.java
        │   └── recovery/
        │       └── HumanTaskRecoveryServiceTest.java
        └── resources/
            └── application.properties
```

**POM:** Re-parent under `casehub-work-parent`. Dependencies:
- Compile: `casehub-engine-blackboard`, `casehub-engine` (runtime), `casehub-work-api`, `casehub-platform`, `quarkus-hibernate-orm`, `quarkus-arc`
- Test: `casehub-work` (runtime), `casehub-engine-persistence-memory`, `casehub-engine-scheduler-quartz`, `casehub-work-persistence-memory`, `quarkus-jdbc-h2`, `casehub-ledger`, `casehub-ledger-testing`, `casehub-engine-ledger`, `quarkus-junit5`, `assertj-core`, `awaitility`

Engine dependency versions are managed by `casehub-parent` BOM (already imported by work's parent pom). Engine deps that use `${project.version}` in the old pom need explicit `${version.io.casehub}` or version from the BOM.

#### 2b. Update work metadata

- Add `<module>engine-adapter</module>` to work's parent `pom.xml` (before `integration-tests`)
- Add to `docs/MODULES.md` under "Integration Modules (built)":
  `engine-adapter/` — CaseHub engine adapter; creates WorkItems from HumanTask/ActionGate bindings, translates lifecycle events back to PlanItem transitions

#### 2c. Clean up engine

- Remove `<module>work-adapter</module>` from engine's parent `pom.xml`
- Delete `engine/work-adapter/` directory entirely
- Update `engine/docs/DESIGN.md`: amend the "HumanTaskBinding (casehub-engine-work-adapter)" section title and description to note relocation to `casehub-work-engine-adapter` in the work repo
- Update `engine/CLAUDE.md`:
  - Remove the `## casehub-work-adapter Module` section (lines 423–452) — replace with a one-line pointer: "Relocated to `casehub-work-engine-adapter` in the work repo. See work's CLAUDE.md for module documentation."
  - Line 135: remove `casehub-work-adapter` from the `NoOpLedgerEntryRepository` applied-to list
  - Line 287: update `ActionGateDeploymentHealthCheck` description to reference `casehub-work-engine-adapter` (new artifact name)
  - Lines 276, 278: update gate mechanism narrative references from `work-adapter` to `engine-adapter` (work repo)
- Historical specs (e.g. `docs/specs/2026-05-17-plan-item-store-atomicity-design.md`) — leave file paths as written; they were correct when authored

#### 2d. Update casehub-parent BOM

Update `casehub-parent/pom.xml` `<dependencyManagement>`:
- Replace `casehub-engine-work-adapter` entry with `casehub-work-engine-adapter` under the casehub-work section
- Version managed by `${casehub.version}` — no version change needed

#### 2e. Consumer migration (ripple effect)

The artifact ID changes from `casehub-engine-work-adapter` → `casehub-work-engine-adapter`. Six consumer applications have Maven dependencies on the old artifact:

| Consumer | POM file(s) |
|----------|------------|
| casehub-aml | `app/pom.xml` |
| casehub-clinical | `pom.xml`, `runtime/pom.xml` |
| casehub-devtown | `app/pom.xml` |
| casehub-life | `app/pom.xml` |
| casehub-fsitrading | `app/pom.xml` |
| casehub-soc | `app/pom.xml` |

Consumer migration checklist (per consumer):
1. Update `<artifactId>casehub-engine-work-adapter</artifactId>` → `casehub-work-engine-adapter` in POM
2. Grep test `application.properties` for `io.casehub.workadapter` — update any `quarkus.arc.exclude-types` or `quarkus.arc.selected-alternatives` entries to use `io.casehub.work.engine`
3. Update consumer CLAUDE.md references to the old module/artifact name

Consumer builds will fail until they update — this is intentional. The breakage forces explicit acknowledgment of the new artifact coordinates. Consumer updates are out of scope for this spec; each consumer repo handles its own dependency bump.

#### 2f. Verification

- `mvn test -pl engine-adapter` passes in work
- `mvn install` in engine succeeds without work-adapter module
- No stale `io.casehub.workadapter` references remain in either repo

### Phase 3 — Architecture Alignment

#### 3a. Delete WorkItemCallerRef (broken, redundant, boundary-violating)

Delete `WorkItemCallerRef` and `WorkItemCallerRefTest` from work-api entirely.

Rationale: `CallerRef` — the sealed interface in the adapter with `PlanItemRef` and `GateRef` — already provides correct, type-safe callerRef parsing with regex patterns and encode/decode symmetry. After relocation, both parsers would be in the same repo. Fixing `WorkItemCallerRef` would create two parallel callerRef parsers — one well-designed sealed interface, one utility method returning a nullable UUID. Worse, a callerRef parser in work-api violates the ARC42STORIES §3 boundary rule: callerRef is "stored and echoed opaquely" by work. The adapter's `CallerRef` is the canonical parser; any future code needing callerRef parsing should use it directly.

No external consumers — only its own test in work-api. Zero usages outside the test class (verified via IntelliJ find-references across both repos).

#### 3b. Update project documentation

**CLAUDE.md (work):**
- Remove the `WorkItemCallerRef.parseCaseId` paragraph from `## casehub-work-api Utilities` — the class is being deleted (see §3a); the paragraph also incorrectly claims `casehub-engine-actor-state` uses it (verified false) and describes the wrong callerRef format
- Add a `## engine-adapter Module` section — migrate the detailed module documentation from engine's CLAUDE.md (removed in Phase 2c): activation, dependency structure, YAML DSL integration, inbound/outbound bridge behavior, transactional semantics, test setup guidance, callerRef format. Adapt package names and artifact references to the new locations.

**ARC42STORIES.MD:**
- §3 Boundary Rules: fix the callerRef convention from `"caseId:planItemId"` to the actual formats: `case:{caseId}/pi:{planItemId}` for HumanTask bindings and `case:{caseId}/gate:{gateId}` for ActionGate bindings
- Add the engine-adapter module to the Building Block View (§5) if module listings exist
- Add a layer/chapter entry if the module warrants one (likely a brief entry under Integration Modules)

#### 3c. Event-as-request pattern — deferred

The `HumanTaskScheduleEvent` consumed via `@ConsumeEvent` is architecturally reasonable. The handler does substantial work: PlanItem lookup, status validation, template vs inline routing, DELEGATED persistence. The Vert.x event bus decoupling is appropriate.

The deeper refactoring (replacing the event contract with direct calls) is tracked by #298; the CloudEvent bridge for cross-service creation is tracked by #299. The relocation achieves the primary goal — domain coherence. Architectural refactoring of the event contract is a separate concern.

## What Stays in Engine

| Item | Why it stays |
|------|-------------|
| `HumanTaskTarget.java` (engine-api) | Engine domain model — the binding target definition |
| `HumanTaskScheduleEvent.java` (engine-common) | Engine internal event — fired by the engine binding evaluator |
| `ActionGate*Event.java` (engine-common) | Engine internal events — gate scheduling and resolution |
| `EventBusAddresses.java` (engine-common) | Engine event bus address constants |
| `PlanItemStore.java` (engine-common SPI) | Engine SPI — the adapter provides an implementation |
| `docs/adr/0001-humantask-yaml-binding-target.md` | Engine ADR about the binding approach |
| `docs/specs/2026-05-*`, `docs/specs/2026-06-*` | Historical design specs — paths were correct when written |
| `HumanTaskTargetDispatchTest.java` (runtime) | Tests engine's dispatch of HumanTask bindings |
| `HumanTaskTarget.java`, `HumanTaskTargetTest.java` (api) | Engine API model and tests |

## What Moves to Work

All 22 files from `engine/work-adapter/src/` (12 source + 10 test) plus `application.properties`.

## Risks

| Risk | Mitigation |
|------|-----------|
| Engine dependency versions drift | `casehub-parent` BOM manages all versions centrally |
| Jandex index configuration breaks | Copy test `application.properties` and verify CDI discovery |
| `plan_item` table DDL/entity conflicts | `PlanItemEntity` in `casehub-engine-persistence-hibernate` owns the `plan_item` DDL (Hibernate `drop-and-create` in dev/test, engine Flyway in production). `WorkAdapterPlanItemEntity` is a read/write mapping only — no schema ownership, no Flyway migrations for this table. The two entities never co-exist on the same classpath: the adapter's integration tests use H2 with Hibernate `drop-and-create` independently of engine-persistence-hibernate. This is unchanged by relocation — the entities remain in separate modules with separate test classpaths. |
| Cross-repo build order | work already depends on engine-api; adding engine-blackboard/engine deps is the same pattern |
| Consumer build breakage | Intentional — 6 consumers must update artifact ID in POMs. Parent BOM update (Phase 2d) unblocks consumers; consumer POM updates are each repo's responsibility. See Phase 2e. |

## Not in Scope

- Event contract refactoring — replace the `@ConsumeEvent` event-as-request pattern with a direct call (#298)
- CloudEvent bridge for cross-service HumanTask creation (#299)
