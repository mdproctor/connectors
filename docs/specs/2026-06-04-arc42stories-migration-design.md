# ARC42STORIES.MD Migration Design
**Date:** 2026-06-04
**Issue:** casehubio/work#246
**Branch:** issue-246-arc42stories-migration

---

## What This Spec Covers

Design for migrating `docs/DESIGN.md` + `docs/ARCHITECTURE.md` to a single `ARC42STORIES.MD`
at the project root of casehub-work, following the Arc42Stories v0.1 specification.

---

## Key Constraints

**Foundation tier, not application tier.**
casehub-work is a foundation module. The CaseHub profile's prescribed layer taxonomy
(`L2 casehub-work`, `L3 casehub-qhorus`, etc.) is for harness applications that *use*
casehub-work. casehub-work's own ARC42STORIES.MD uses `arc42stories-spec.md` directly.
The CaseHub profile's layer taxonomy does not apply here.

**Code is the source of truth.**
DESIGN.md and ARCHITECTURE.md contain stale content (WorkItemFormSchema appears in
ARCHITECTURE.md's domain model table but was deleted in Phase 22). ARC42STORIES.MD is
written from the current codebase, using existing docs as checklist/starting-point only.

**Placement:** Project root — `ARC42STORIES.MD` at `/Users/mdproctor/claude/casehub/work/ARC42STORIES.MD`.
Per protocol `PP-20260603-33c84c` (arc42stories-project-repo-placement).

**Retirement:** DESIGN.md and ARCHITECTURE.md are deleted after content is verified migrated.
No redirect stubs. Verified via three-check quality sweep (protocol PP-20260602-2fa080).

**CLAUDE.md update:** In the same commit as ARC42STORIES.MD creation, update CLAUDE.md to
declare ARC42STORIES.MD as primary architecture record (protocol PP-20260602-1a0c25).

**Downstream:** After merge, update `casehub-parent/docs/repos/casehub-work.md` to point to
ARC42STORIES.MD instead of DESIGN.md/ARCHITECTURE.md. Filed as casehubio/parent#170.

---

## Document Structure

ARC42STORIES.MD follows the full §1–§13 spec with Arc42Stories §9 extension.

### Preamble (Foundation tier)

```markdown
# CaseHub Work — ARC42STORIES.MD

**Spec:** Arc42Stories v0.1
**Profile:** CaseHub — Foundation tier
**Profile ref:** `../parent/docs/arc42stories-casehub-profile.md`
**Build position:** Foundation — depends on casehub-platform-api only (core); casehub-ledger optional
**Consumed by:** casehub-engine (work-adapter), casehub-clinical, devtown, casehub-life
**Depends on:** casehub-platform-api (compile, api/ module only)
```

### §1 Introduction and Goals

Content: description of casehub-work as the human task lifecycle layer; stakeholder table
(Quarkus developer, consumer repo, human actor, AI agent, platform team); quality goals
(SLA correctness, isolation, zero-datasource unit testing); artifact schema (foundation tier:
no PREFIX, use #NNN issue refs directly; GE-YYYYMMDD-XXXXXX garden; PP-YYYYMMDD-XXXXXX protocol;
ADR-NNNN; YYYY-MM-DD-topic-design spec).

Source: ARCHITECTURE.md §Overview + Glossary. Written fresh — not copy-pasted.

### §2 Constraints

Content: Java 21 on JVM 26; Quarkus 3.32.2; GraalVM 25 native target; PostgreSQL production /
H2 MODE=PostgreSQL tests; `mvn` not `./mvnw`; zero casehubio deps in core (`casehub-work`
runtime depends only on casehub-platform-api); module naming short names (PP: maven-submodule-folder-naming).

### §3 Context and Scope

Content: C4 System Context diagram (Mermaid `C4Context`) showing casehub-work surrounded by
its consumers (casehub-engine, casehub-clinical, devtown) and its dependency (casehub-platform-api,
casehub-ledger optional). Boundary rules section: explicit list of what casehub-work does NOT do.

Designed §3 diagram is already finalised in brainstorm (see below).

### §4 Solution Strategy

Content: foundation modules define their own layer taxonomy; 7-layer internal taxonomy table;
Chapter sequencing rationale (hard dependencies and soft orderings).

**Layer taxonomy (7 layers):**

| Layer | Concern |
|---|---|
| L1 Domain Baseline | WorkItem + AuditEntry entities, Storage SPI, JPA defaults, WorkItemService |
| L2 REST API | WorkItemResource, DTOs, exception mappers, OpenAPI |
| L3 Lifecycle Engine | ExpiryCleanupJob, ClaimDeadlineJob, SlaBreachPolicy SPI, CDI lifecycle events |
| L4 Label System | LabelVocabulary, MANUAL/INFERRED persistence, FilterEngine (JEXL/JQ/Lambda) |
| L5 Ledger Integration | WorkItemLedgerEntry, hash chain, peer attestation, EigenTrust |
| L6 Distribution | WorkItemEventBroadcaster SPI, PostgreSQL LISTEN/NOTIFY broadcasters |
| L7 Optional Modules | Reports, AI (semantic routing + LLM assist), notifications, issue tracker, MongoDB, Flow |

### §5 Building Block View

Content: C4Container diagram showing all modules grouped by layer; module table with folder
name → artifact ID → type → purpose for all 20 modules.

**Key accuracy requirements (from code verification):**
- Do NOT include WorkItemFormSchema in domain model (entity deleted Phase 22)
- Module folder names are short: `api/`, `core/`, `runtime/` — not `casehub-work-api/`
- queues-dashboard module exists and must be listed
- All 20 modules must appear

### §6 Runtime View

Three key scenarios (already designed in brainstorm):
1. WorkItem creation with assignment flow
2. SLA breach → Fail (ExpiryCleanupJob → SlaBreachPolicy → EXPIRED)
3. Delegation accept/decline (DELEGATED → DeclineTarget resolution)

### §7 Deployment View

Content: C4Deployment diagram (standard + distributed); variant table showing minimal /
standard / distributed cluster / full audit / AI routing configurations.

### §8 Crosscutting Concepts

Content: pointer table (module tier structure, Flyway migration rules, CDI displacement,
SPI placement, persistence backend CDI priority, auth retrofit readiness).

**Anti-patterns (4, inline per profile rule):**
1. `casehub-platform` scope bug: `test` scope in module with `quarkus:build` → UnsatisfiedResolutionException at augmentation. Fix: `runtime` scope.
2. New active status not in WorkItemQuery: silent scheduler blindspot. Fix: audit isActive() + isTerminal() + WorkItemQuery status sets together.
3. Rolled-back events published via SSE: use `TransactionPhase.AFTER_SUCCESS` in all broadcaster observers.
4. Concurrent claim race: OCC via `@Version` on WorkItem; 409 Conflict → retry.

### §9 Journeys and Chapters

**The core extension section.**

#### §9.1 Journey Overview

Three journeys with Mermaid flowchart (Journey Map, C4-style flowchart per spec).

| Journey | Description | Chapters | Status |
|---|---|---|---|
| Core Platform | Domain, REST, lifecycle, events, ledger, queues, native, templates, enrichment, audit, spawn, claim OCC, module architecture, ClaimSlaPolicy | C1–C15 | ✅ Complete |
| Enterprise Capabilities | Confidence routing, worker selection, semantic AI + LLM assist, MongoDB, issue tracker, SLA reporting, multi-instance, business hours, notifications, broadcaster, distributed SSE | C16–C27 | ✅ Complete |
| Lifecycle Enrichment | Named outcomes, template schemas, CoI exclusions, builder, round-robin, SlaBreachPolicy, escalation removal, template enhancements, capability vocabulary, status lifecycle | C28–C35 | ✅ Complete |

**Journey chapter counts:** J1=15 (C1–C15), J2=12 (C16–C27), J3=9 (C28–C35). Total: 35.

#### §9.2 Chapter Index

**Complete 35-chapter list:**

| # | Chapter | Journey | Key issues | Modules |
|---|---|---|---|---|
| C1 | Domain Baseline | J1 | Phase 1 | runtime, testing |
| C2 | REST API | J1 | Phase 2 | runtime |
| C3 | Lifecycle Engine | J1 | Phase 3 | runtime |
| C4 | CDI Events | J1 | Phase 4 | runtime |
| C5 | Quarkus-Flow Integration | J1 | Phase 5, #37, #38 | flow, flow-examples |
| C6 | Ledger Module | J1 | Phase 6, #45, ADR-0001 | ledger |
| C7 | Label-Based Queues | J1 | Phase 7, #72, ADR-0002 | queues, queues-examples, queues-dashboard |
| C8 | Native Image | J1 | Phase 8 | integration-tests, deployment |
| C9 | Form Schema *(superseded by C29)* | J1 | #107, #108 | runtime |
| C10 | WorkItemTemplate | J1 | #76 | runtime |
| C11 | WorkItem Model Enrichment | J1 | #74, #75, #82, #83–#89, #91 | runtime |
| C12 | Audit History API | J1 | Phase 10, #109–#111 | runtime |
| C13 | Subprocess Spawn | J1 | #SpawnPort, V17+V18 | api, runtime |
| C14 | Atomic Claim + Schedule Dedup | J1 | #94, #96 | runtime, core |
| C15 | ClaimSlaPolicy SPI | J1 | #125 | api, core |
| C16 | Confidence-Gated Routing | J2 | Phase 11, #112–#114 | runtime, ai |
| C17 | Worker Selection Strategy | J2 | Phase 12, #115–#116 | api, core, runtime |
| C18 | Module Separation | J2 | #118 | api, core |
| C19 | Semantic Skill Matching + LLM Assist | J2 | #121, #124, #126, V4001 | ai |
| C20 | MongoDB Persistence | J2 | persistence-mongodb | persistence-mongodb |
| C21 | Issue Tracker | J2 | #73, #156–#161 | issue-tracker |
| C22 | SLA Compliance Reporting | J2 | Phase 14, #142–#145 | reports |
| C23 | Multi-Instance WorkItems | J2 | Phase 15, #106 | runtime |
| C24 | Business-Hours Deadlines | J2 | Phase 16 | api, runtime |
| C25 | Notifications | J2 | Phase 17, #140–#141 | notifications |
| C26 | Broadcaster SPI | J2 | Phase 18, #147, #150 | runtime |
| C27 | Distributed SSE + Queue Broadcaster | J2 | Phase 19+20, #93, #155 | postgres-broadcaster, queues-postgres-broadcaster |
| C28 | Named Outcomes | J3 | Phase 21, #169, #176, #178 | api, runtime |
| C29 | Template Data Schemas | J3 | Phase 22, #170 — *supersedes C9* | runtime |
| C30 | Conflict-of-Interest Exclusions | J3 | Phase 23, #171, #186, #192, ADR-0005 | api, runtime |
| C31 | WorkItemCreateRequest Builder | J3 | Phase 24, #182 | runtime, api |
| C32 | Round-Robin Strategy | J3 | #117, #200, #202, #203 | core, runtime |
| C33 | SlaBreachPolicy SPI + Escalation Removal | J3 | Phase 25+26, #212–#216 | api, core, runtime |
| C34 | Capability Vocabulary | J3 | #220, ADR-0003, ADR-0004 | api, core, runtime |
| C35 | Status Lifecycle Fixes + DELEGATED | J3 | Phase 27, #241, #243–#245 | api, core, runtime |

**Journey counts: J1=15 (C1–C15), J2=12 (C16–C27), J3=9 (C28–C35). Total: 35.**

**Merged vs. original DESIGN.md:**
- C3+C4 kept separate (CDI events distinct delivery, same-day but different architectural concern)
- C13 (Module Separation) kept separate from C19 (Semantic AI) — different journeys
- C19 merges Semantic Skill Matching + LLM Assist (same module, same session, complementary capabilities)
- C27 merges WorkItem broadcaster + Queue broadcaster (same pattern, same day)
- C33 merges SlaBreachPolicy introduction + EscalationPolicy removal (two sides of one decision)

**Layer × Chapter matrix:** (to be rendered in full in the spec — abbreviated here)

L1 Domain Baseline: High on C1; Low on C2, C4, C9, C10, C11, C12, C13, C14, C15, C28, C29, C30, C31, C34, C35
L2 REST API: High on C2; Med on C12; Low on C28, C29, C31
L3 Lifecycle Engine: High on C3, C33; Med on C4, C15, C23, C24, C32, C35
L4 Label System: High on C7; Med on C11, C16
L5 Ledger Integration: High on C6
L6 Distribution: Med on C26; High on C27
L7 Optional Modules: Med on C5, C18, C19, C20, C22; High on C25

**Sequencing rationale (key hard constraints):**
- C1 before C2: REST requires persisted entity and service
- C2 before C3: lifecycle transitions require service methods
- C3 before C4: CDI events emitted inside transitions
- C6 before any L5 work: LedgerEventCapture @Observes requires C4 event bus
- C7 before C16: confidence-gated routing extends filter engine from C7
- C18 before C19: Semantic AI depends on api/core split
- C26 before C27: PostgreSQL broadcaster implements the SPI from C26
- C33 before C35: status lifecycle correctness (Chained/Exhausted) built on C33 SlaBreachPolicy

#### §9.3 Chapter Entries

One entry per chapter, 10–20 lines each. Format per arc42stories spec:
- Journey, Sequence, Status, Delivered date, Issues
- "What this delivers" (2–3 sentences, user-visible outcome, Before→After)
- "Accountability gaps closed" (bullets)
- "Layer Impact" table

**Note on C9 (Form Schema, superseded):** Entry records the historical delivery and explicitly
states the entity was deleted by C29. No accountability gaps closed (net zero).

**Note on C19 (Semantic + LLM):** Entry covers both semantic worker selection (EmbeddingSkillMatcher,
SemanticWorkerSelectionStrategy) and LLM-assisted features (ResolutionSuggestionResource,
EscalationSummaryObserver, V4001). These are complementary AI capabilities in the same module
delivered in the same session.

#### §9.4 Layer Entries

One entry per layer (L1–L7), in reading order. Each entry includes:
- Participates in chapters (chapter refs)
- Key files (paths from code — verify existence before writing)
- Key wiring (non-obvious configuration, the HOW)
- Architectural decisions (the WHY per layer)
- Gotchas (Symptom → Cause → Fix format)
- Pattern to replicate (numbered domain-agnostic steps)

**Layer entry writing rules:**
1. Key files must be verified with `find` before inclusion (garden entry GE-20260601-b0eabf)
2. CDI annotations must be verified against production code (garden entry GE-20260601-c09f71)
3. Gotchas are sourced from blog entries (127 entries), garden entries, and spec notes — this is
   where the session-narrative knowledge belongs in the permanent record
4. Pattern to replicate is domain-agnostic — no casehub-work-specific terminology in the steps

**Blog entries as gotcha sources:**
The 127 blog entries contain gotchas, techniques, and architectural insights from development
sessions. Key relevant ones to mine for layer entries:
- 2026-04-20: "DB Independence and the Reactive Question" — L1 wiring gotchas
- 2026-04-21: "The filter that grew into a contract" — L4 pattern
- 2026-04-29: "Three audit findings and a wrong mental model" — L5 gotchas
- 2026-05-01: "Three bugs hiding behind the wrong error" — L6 distribution gotchas
- 2026-05-21: "What Flyway was hiding" — L1 migration gotcha
- 2026-05-21: "The subdirectory that wasn't scoped" — §8 Flyway path gotcha
- 2026-05-22: "JQ Moves to Platform" — L4 dependency change

### §10 Architectural Decisions

Content: cross-reference to `docs/adr/` index. All 5 ADRs referenced with one-line summaries.
Only decisions not captured inline in §9.4 layer entries belong here.

| ADR | Decision | Chapter |
|---|---|---|
| ADR-0001 | Extract ledger infrastructure to shared casehub-ledger | C6 (inline in L5 layer entry) |
| ADR-0002 | Labels as queues with MANUAL/INFERRED persistence | C7 (inline in L4 layer entry) |
| ADR-0003 | Capability vocabulary as validated value type | C34 (inline in L1 layer entry) |
| ADR-0004 | Capability validation mode as deployment config | C34 (inline in L1 layer entry) |
| ADR-0005 | Group Membership Snapshot at WorkItem Creation | C30 (inline in L1 layer entry) |

Since all 5 ADRs are chapter-specific, §10 will be sparse by design — one cross-reference
table pointing to the ADR files and the chapter where each decision is elaborated.

### §11 Quality Requirements

Content: quality scenarios table (SLA correctness, isolation, zero-datasource testing,
native image correctness). Test totals by module (~1278 tests).

### §12 Risks and Technical Debt

Content sourced from DESIGN.md "blocked" phases + open issues:
- Qhorus integration blocked (casehub-work-qhorus not built — qhorus APIs not stable)
- CaseHub integration blocked (casehub-work-casehub not built)
- ProvenanceLink (#39) blocked on above two
- casehub-work-notifications duplicates casehub-connectors (parent#5 open)
- queues-dashboard Tamboui dependency (POC, not production-hardened)
- WorkItemLabel.path still String (LabelDefinition migrated to Path in C34, WorkItemLabel.path migration deferred — M-scale)

### §13 Glossary

Content: WorkItem vs Task disambiguation (three-system glossary from ARCHITECTURE.md).
Additional terms: Journey, Chapter, Layer, SPI, SLA, Breach, Escalation, Delegation, callerRef.

---

## Retirement Plan for Source Documents

**Pre-condition:** Three-check quality sweep passes (protocol PP-20260602-2fa080):
1. Run `gh issue view N --repo casehubio/work` for every `#N` in §12 — closed issues removed from Active Risks
2. Run `find . -name "ClassName.java"` for every class in §9.4 Key files — verify existence
3. Open each service in code, verify CDI annotations match document

**On passing all three checks:**
```bash
git rm docs/DESIGN.md docs/ARCHITECTURE.md
```

**In the same commit:**
- Update CLAUDE.md: replace DESIGN.md/ARCHITECTURE.md references with ARC42STORIES.MD
- Update CLAUDE.md §Design Document section to reference ARC42STORIES.MD §5, §9, §10

**After merge:** Update casehubio/parent#170 (casehub-work.md deep-dive).

---

## Writing Approach

ARC42STORIES.MD is written from the codebase, using:
1. Git log (primary narrative source — what was delivered and when)
2. Specs in `docs/specs/` (design rationale per chapter)
3. Blog entries (gotchas and session insights for §9.4)
4. Existing DESIGN.md + ARCHITECTURE.md (completeness checklist only)
5. ADR files (§10 content)
6. Live code (Key files verification, CDI annotation verification)

Mode discipline per arc42stories-spec.md §Writing Style mode map:
- §1–§3, §5, §7: Reference/lookup — tables and bullets, no prose narrative
- §4: Explanation/comparative — Before:/After: structure
- §6: Explanation/comparative — scenario as sequence description
- §8 pointer table: Reference/pointer
- §8 anti-patterns: How-to/diagnostic — **Symptom:** → **Cause:** → **Fix:**
- §9.3 "What this delivers": 2–3 sentences, user-visible outcome
- §9.4 Key files: `path/to/File.java` — one sentence
- §9.4 Gotchas: **Symptom:** → **Cause:** → **Fix:**
- §9.4 Pattern to replicate: numbered imperative steps, domain-agnostic
- §10 ADRs: Context → Decision → Consequences

Apply `write-content` skill anti-slop rules throughout.

---

## Out of Scope for This Issue

- WorkItemNote/WorkItemRelation/WorkItemLink details (captured in C11, Key files in §9.4 L1)
- Future chapters (blocked integrations: Qhorus, CaseHub, ProvenanceLink)
- casehubio/parent#170 (downstream deep-dive update — separate issue)
- ARC42STORIES.MD for any other casehub module — this spec is casehub-work only
