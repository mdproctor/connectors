# quarkus-work-examples — Design Spec

**Date:** 2026-04-15
**Status:** Approved

---

## Purpose

A self-contained Maven module that demonstrates every ledger, audit, and lifecycle capability of
`quarkus-work` through four runnable scenario endpoints. Each scenario executes a realistic
business story end-to-end via `POST /examples/{name}/run`, logs a narrative to stdout as it runs,
and returns the full ledger and audit trail as JSON.

Target audience: developers evaluating Tarkus, contributors validating behaviour, CI regression.

---

## Module Identity

| Field | Value |
|---|---|
| Maven module directory | `quarkus-work-examples/` |
| artifactId | `quarkus-work-examples` |
| Root Java package | `io.quarkiverse.work.examples` |
| Added to parent `<modules>` | Before `integration-tests` |

---

## Dependencies

```xml
<!-- Core extension -->
quarkus-work (runtime)

<!-- Optional accountability module — all features enabled -->
quarkus-work-ledger

<!-- Datasource — H2 in-memory for zero-config running -->
quarkus-jdbc-h2

<!-- REST + JSON -->
quarkus-rest
quarkus-rest-jackson

<!-- Schema management -->
quarkus-flyway

<!-- Test -->
quarkus-junit5
rest-assured
assertj-core
```

No dependency on `quarkus-work-flow` — examples are self-contained.

---

## Configuration (`application.properties`)

All ledger features enabled so every capability is exercised:

```properties
# Datasource — H2 in-memory
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:tarkus-examples;DB_CLOSE_DELAY=-1
quarkus.flyway.migrate-at-start=true

# WorkItem defaults
quarkus.work.default-expiry-hours=168
quarkus.work.default-claim-hours=24

# Ledger — all features on
quarkus.work.ledger.enabled=true
quarkus.work.ledger.hash-chain.enabled=true
quarkus.work.ledger.decision-context.enabled=true
quarkus.work.ledger.evidence.enabled=true
quarkus.work.ledger.attestations.enabled=true
quarkus.work.ledger.trust-score.enabled=true
quarkus.work.ledger.trust-score.decay-half-life-days=90
```

Test-only override (`src/test/resources/application.properties`):

```properties
quarkus.http.test-port=0
```

(Prevents `TIME_WAIT` port conflicts when multiple `@QuarkusTest` classes run in sequence.)

---

## Project Structure

```
quarkus-work-examples/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/io/quarkiverse/tarkus/examples/
    │   │   ├── expense/
    │   │   │   └── ExpenseApprovalScenario.java
    │   │   ├── credit/
    │   │   │   └── CreditDecisionScenario.java
    │   │   ├── moderation/
    │   │   │   ├── ContentModerationScenario.java
    │   │   │   └── MockAIClassifier.java
    │   │   └── queue/
    │   │       └── DocumentQueueScenario.java
    │   └── resources/
    │       └── application.properties
    └── test/
        ├── java/io/quarkiverse/tarkus/examples/
        │   ├── expense/ExpenseApprovalScenarioTest.java
        │   ├── credit/CreditDecisionScenarioTest.java
        │   ├── moderation/ContentModerationScenarioTest.java
        │   └── queue/DocumentQueueScenarioTest.java
        └── resources/
            └── application.properties
```

---

## Scenario Runner Contract

Each scenario is an `@ApplicationScoped` JAX-RS resource with a single endpoint:

```
POST /examples/{scenario}/run
→ 200 OK
  {
    "scenario": "expense-approval",
    "steps": [
      { "step": 1, "description": "Finance system creates expense WorkItem", "workItemId": "..." },
      ...
    ],
    "workItemId": "...",
    "ledgerEntries": [ ... ],    // full LedgerEntryResponse list
    "auditTrail":   [ ... ]      // full AuditEntryResponse list
  }
```

Each step is logged to stdout before execution:

```
[SCENARIO] Step 1/4: Finance system creates expense WorkItem for alice (priority=HIGH)
[SCENARIO] Step 2/4: alice claims WorkItem {id}
[SCENARIO] Step 3/4: alice starts work
[SCENARIO] Step 4/4: alice completes — resolution: {"approved":true,"amount":450.00}
[SCENARIO] Done. 4 ledger entries, hash chain intact.
```

Scenarios inject `WorkItemService` and the ledger/trust REST beans directly
(`LedgerResource`, `ActorTrustResource`, `TrustScoreJob`) — no HTTP round-trips
to self. This keeps the runner transactional and avoids port binding during tests.

---

## The Four Scenarios

### Scenario 1: Expense Approval
**Endpoint:** `POST /examples/expense/run`

**Business story:** An employee submits an expense report; a finance manager reviews and approves it.

**Steps:**
1. `finance-system` creates a `HIGH` priority WorkItem (`title="Expense report: team offsite"`, `assigneeId="alice"`)
2. `alice` claims it (PENDING → ASSIGNED)
3. `alice` starts it (ASSIGNED → IN_PROGRESS)
4. `alice` completes it with resolution `{"approved":true,"amount":450.00}` (IN_PROGRESS → COMPLETED)

**Ledger features demonstrated:**
- 4 `LedgerEntry` records written automatically by `LedgerEventCapture`
- Hash chain on every entry (`previousHash` + `digest`)
- `decisionContext` snapshot at each transition
- `actorType=HUMAN` throughout
- Full `AuditEntry` trail from runtime module

---

### Scenario 2: Regulated Credit Decision
**Endpoint:** `POST /examples/credit/run`

**Business story:** An automated credit-scoring engine raises a WorkItem for human review of a
loan application. The reviewing officer suspends while awaiting documents, delegates to a supervisor
who completes with a policy reference, and a compliance officer adds a peer attestation (dual-control).

**Steps:**
1. `credit-scoring-system` creates WorkItem; provenance set immediately
   (`sourceEntityId="LOAN-8821"`, `sourceEntityType="LoanApplication"`, `sourceEntitySystem="credit-engine"`)
2. `officer-alice` claims and starts (PENDING → ASSIGNED → IN_PROGRESS)
3. `officer-alice` suspends — `"Awaiting payslip documents"` (IN_PROGRESS → SUSPENDED)
4. `officer-alice` resumes (SUSPENDED → IN_PROGRESS)
5. `officer-alice` delegates to `supervisor-bob` (IN_PROGRESS → PENDING, delegation ledger entry carries `causedByEntryId`)
6. `supervisor-bob` claims, starts, and completes with
   `rationale="Income verified against payslips"` and `planRef="credit-policy-v2.1"`
7. `compliance-carol` posts peer attestation on the completion entry
   (`verdict=SOUND`, `confidence=0.95`, `evidence="Dual-control review passed"`, `actorType=HUMAN`)

**Ledger features demonstrated:**
- Provenance (`sourceEntityId/Type/System`)
- `suspend` + `resume` lifecycle transitions
- Delegation + `causedByEntryId` linking delegation entry to its cause
- `rationale` + `planRef` (GDPR Article 22 / EU AI Act Article 12 compliance)
- Peer attestation by a human compliance actor
- 8+ ledger entries, full hash chain

---

### Scenario 3: AI Content Moderation
**Endpoint:** `POST /examples/moderation/run`

**Business story:** An AI content classifier flags a post as potential hate speech. A human
moderator reviews and overrides the AI's flag. An automated compliance bot attests that the
override decision is sound.

**Steps:**
1. `MockAIClassifier` (stub `@ApplicationScoped` bean, returns a canned result) analyses a post:
   confidence 0.73, flag `hate-speech`. Creates WorkItem with
   evidence `{"flagReason":"hate-speech","confidence":0.73,"modelVersion":"mod-v3"}` and
   provenance `sourceEntitySystem="content-ai"`, `sourceEntityType="ContentFlag"`.
   WorkItem created with `actorType=AGENT` on the creation ledger entry.
2. Human moderator `moderator-dana` claims and starts it
3. `moderator-dana` rejects the AI flag with `rationale="Context review: satire, not hate speech"`
   (IN_PROGRESS → REJECTED)
4. Compliance bot `compliance-bot` posts attestation on the rejection entry
   (`verdict=ENDORSED`, `confidence=0.88`, `actorType=AGENT`)

**Ledger features demonstrated:**
- Evidence capture (`evidence` field on ledger entry)
- `actorType=AGENT` for AI-created entry and bot attestation
- `actorType=HUMAN` for moderator's rejection
- Provenance from an AI system
- Attestation by an agent (not a human)
- `reject` lifecycle transition
- Contrast: AI-originated → human-resolved → agent-attested chain

**`MockAIClassifier`:** a plain `@ApplicationScoped` bean with a single method
`ContentFlag analyse(String content)` returning a hardcoded result. No external
API call. The scenario injects it and uses its output as the WorkItem's evidence payload.

---

### Scenario 4: Document Review Queue
**Endpoint:** `POST /examples/queue/run`

**Business story:** A team runs a shared document review queue. Multiple reviewers pull from the
pool. One releases a WorkItem they can't handle; another picks it up. After several completions,
trust scores are materialised and queried.

**Steps:**
1. System creates three WorkItems with `candidateGroups=["doc-reviewers"]` (no `assigneeId`)
2. `reviewer-alice` queries inbox (`GET /inbox?candidateGroup=doc-reviewers`) — sees all three, claims the first
3. `reviewer-alice` releases it — can't review right now (ASSIGNED → PENDING)
4. `reviewer-bob` queries the inbox (now sees all three again), claims and completes each
5. `reviewer-alice` queries the inbox again, claims and completes the one she released
6. Scenario injects `TrustScoreJob` (from `quarkus-work-ledger`) and triggers it programmatically,
   bypassing the nightly scheduler, to materialise scores from the accumulated ledger history
7. Queries `ActorTrustResource` for `reviewer-bob` and `reviewer-alice` trust scores;
   includes both in the response. Bob scores higher: more completions, no releases.

**Ledger features demonstrated:**
- `candidateGroups` work queue routing
- Inbox filtering by candidate group
- `release` lifecycle transition
- EigenTrust `ActorTrustScore` computation (triggered programmatically)
- `GET /workitems/actors/{actorId}/trust` endpoint
- Multiple actors building ledger history → differentiated trust scores

---

## Capability Coverage Matrix

This table appears verbatim in `README.md` as the orientation guide:

| Capability | S1 Expense | S2 Credit | S3 Moderation | S4 Queue |
|---|---|---|---|---|
| create / claim / start / complete | ✅ | ✅ | ✅ | ✅ |
| reject | | | ✅ | |
| delegate + causedByEntryId | | ✅ | | |
| release | | | | ✅ |
| suspend + resume | | ✅ | | |
| candidateGroups / work queues | | | | ✅ |
| inbox filtering | | | | ✅ |
| Hash chain (SHA-256) | ✅ | ✅ | ✅ | ✅ |
| decisionContext (GDPR Art. 22) | ✅ | ✅ | ✅ | ✅ |
| rationale + planRef | | ✅ | ✅ | |
| evidence capture | | | ✅ | |
| provenance (sourceEntity*) | | ✅ | ✅ | |
| peer attestations | | ✅ | ✅ | |
| actorType AGENT | | | ✅ | |
| actorType HUMAN | ✅ | ✅ | ✅ | ✅ |
| EigenTrust trust scores | | | | ✅ |
| Trust score endpoint | | | | ✅ |

---

## Test Coverage

Each scenario has a `@QuarkusTest` class that:

1. Calls `POST /examples/{scenario}/run`
2. Asserts HTTP 200
3. Asserts `ledgerEntries` count matches expected
4. Asserts specific fields per scenario:
   - S1: all 4 entries have non-null `digest`; `previousHash` of entry 2 equals `digest` of entry 1
   - S2: provenance fields set on entry 1; attestation present; `causedByEntryId` non-null on delegation entry; `rationale` and `planRef` on completion entry; suspend + resume entries present
   - S3: entry 1 has non-null `evidence`; rejection entry has non-null `rationale`; attestation `actorType=AGENT`
   - S4: `ledgerEntries` ≥ 10 (3 WorkItems × lifecycle); both trust scores non-null; bob's score > alice's

Tests run via `mvn test -pl quarkus-work-examples` (Surefire, not Failsafe — these are
`@QuarkusTest`, not `@QuarkusIntegrationTest`).

---

## README Structure

```
# quarkus-work-examples

> What this module demonstrates

[Capability coverage matrix]

## Prerequisites
## Running with quarkus:dev

## Scenario 1: Expense Approval
  - What it shows
  - Run: curl -X POST localhost:8080/examples/expense/run | jq
  - Sample stdout
  - Key fields to look at in the response

## Scenario 2: Regulated Credit Decision
  - What it shows
  - Run: curl ...
  - Key fields: decisionContext, rationale, planRef, causedByEntryId, attestations

## Scenario 3: AI Content Moderation
  - What it shows
  - Run: curl ...
  - Key fields: evidence, actorType, provenance

## Scenario 4: Document Review Queue
  - What it shows
  - Run: curl ...
  - Key fields: candidateGroups, trust scores

## Other lifecycle transitions
  - cancel — one-liner curl example using the WorkItem API directly
  - expiry + escalation — explanation + config to trigger quickly in dev mode

## Running all four
  - Bash snippet: run all four in sequence
```

---

## Implementation Notes

### AttestationVerdict values
The `AttestationVerdict` enum (from `io.quarkiverse.ledger.runtime`) has four values:
- **Positive:** `SOUND` (clean decision), `ENDORSED` (attestor explicitly endorses)
- **Negative:** `FLAGGED` (issue found), `CHALLENGED` (attestor disputes)

Scenarios use `SOUND` for human peer review sign-off and `ENDORSED` for agent attestations.

### `actorType=AGENT` derivation
`LedgerEventCapture` currently defaults all entries to `actorType=HUMAN`. The code comment
already notes the intended design: "agents identified by actorId prefix convention."

The implementation plan must include a small enhancement to `LedgerEventCapture` — derive
`actorType` from the actorId: any actorId starting with `"agent:"` maps to `ActorType.AGENT`,
any starting with `"system:"` maps to `ActorType.SYSTEM`, all others remain `HUMAN`. Scenario 3
uses `actorId="agent:content-ai"` and `actorId="agent:compliance-bot"` to trigger this.

### `TrustScoreComputer` accessibility
`TrustScoreComputer` (from `io.quarkiverse.ledger.runtime.service`) is a plain class, not a CDI
bean — it accepts `decayHalfLifeDays` as a constructor argument. `TrustScoreJob` wraps it with
config and is the injectable CDI entry point. Scenario 4 injects `TrustScoreJob` and exposes
a `runNow()` method (or calls the scheduled method directly) to trigger computation on demand.
If `TrustScoreJob` does not already expose a `runNow()` method, the implementation plan adds one.

---

## What Is Not Covered (and Why)

| Gap | Reason |
|---|---|
| `actorType=SYSTEM` (expiry/escalation entries) | Requires time manipulation; explained in README "Other transitions" section instead |
| `cancel` | Self-explanatory one-liner; added to README rather than a full scenario |
| Hash chain tamper verification | Response includes all `previousHash` + `digest` fields; README explains how to verify; no code needed |
| Native image | Covered by `integration-tests` module with `-Pnative` |
