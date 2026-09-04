# Investigation Triage Logic — Design Spec

**Issue:** casehubio/aml#112
**Date:** 2026-07-21
**Status:** Approved

## Summary

Replace the `InvestigationTriageWorker` stub (always returns `SAR_WARRANTED`) with
deterministic rule-based triage logic that evaluates specialist findings and decides
whether a SAR is warranted, the transaction is a false positive, or evidence is
inconclusive. Retrofit all AML workers to use typed domain record inputs instead of
raw `Map<String, Object>` extraction.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Rule structure | Hard-rule gates + scoring fallback | Sanctions/PEP have regulatory implications — must not be bypassable by score arithmetic |
| Worker input typing | Typed domain records via ObjectMapper | Map extraction is fragile (silent typos, wrong casts, no IDE navigation) |
| Threshold configuration | Platform preferences (`PreferenceProvider` + `PreferenceKey`) | Existing platform mechanism; avoids inventing a config approach |
| CBR integration | Threshold adjustment | CBR nudges thresholds based on historical outcomes; hard gates remain absolute |
| Triage output | Structured explanation | Triage is the most consequential decision — must be reconstructable from output alone |
| Evaluator structure | Strategy decomposition (gates + scorer + adjuster) | Each component has one concern; CBR adjustment is isolated and independently testable |
| PlannedAction for triage | INCONCLUSIVE gate only | SAR_WARRANTED has downstream MLRO gate via SAR_FILING; FALSE_POSITIVE is a positive analytical conclusion; INCONCLUSIVE means ambiguous evidence — regulatory frameworks expect human review before clearance |
| Worker function type | `WorkerFunction.Sync` | FlowWorkerFunction does not support PlannedAction (engine#564); INCONCLUSIVE path requires PlannedAction(INVESTIGATION_CLEARANCE) |
| SuspiciousTransaction in TriageInput | Excluded | Specialist findings subsume raw transaction attributes; triage evaluates processed conclusions, not raw input — if a signal is lost, the fix belongs in the specialist worker's output model |

## Domain Model (api/)

### New types

**`TriageInput`** — typed aggregate of the four specialist findings:

```java
public record TriageInput(
    EntityResolutionResult entityResolution,
    PatternAnalysisResult patternAnalysis,
    OsintResult osintScreening,
    CbrPathAdvice cbrPathAdvice  // nullable
) {}
```

**`CbrPathAdvice`** — typed record for CBR path advisor output:

```java
public record CbrPathAdvice(
    int caseCount,
    double avgSimilarity,
    double confidence,
    String predominantOutcome,          // nullable
    Double predominantOutcomeFrequency, // nullable
    boolean error                       // true when advisor failed
) {}
```

**`TriageResult`** — structured triage output:

```java
public record TriageResult(
    TriageDecision decision,
    String reason,
    double riskScore,
    HardGate hardGate,                  // nullable — only when a gate fired
    Double cbrThresholdAdjustment,      // nullable — only when CBR influenced
    List<RiskFactor> factors
) {}
```

**`HardGate`** — enum of absolute gates:

```java
public enum HardGate {
    SANCTIONS_HIT,
    CONFIRMED_PEP,
    SHELL_COMPANY
}
```

**`RiskFactor`** — individual contributing factor:

```java
public record RiskFactor(String name, double weight, String detail) {}
```

### Modified existing types

**`OsintResult`** — needs `declined` and `reason` fields to match what OSINT workers
actually produce. Current record `OsintResult(sanctionsHit, pepHit, detail)` silently
drops `declined` on deserialization, but triage needs it for uncertainty scoring.

```java
public record OsintResult(
    boolean sanctionsHit,
    boolean pepHit,
    boolean declined,    // added — true when agent lacked clearance
    String reason        // added — replaces 'detail' to match worker output
) {
    public OsintResult {
        if (declined && (sanctionsHit || pepHit)) {
            throw new IllegalArgumentException(
                "declined screening cannot report sanctions or PEP hits");
        }
    }
}
```

Semantics: `declined` and `sanctionsHit`/`pepHit` are mutually exclusive. A declined
screening did not complete, so it cannot report hits. If partial screening (found PEP
but could not complete full clearance) is needed in future, model it as a separate
outcome rather than overloading `declined`.

This is a breaking change to `OsintResult`. All constructor call sites must be updated
(see Modified files table for complete list).

**`AmlActionType`** — add `INVESTIGATION_CLEARANCE` constant for INCONCLUSIVE triage
decisions requiring human review before case clearance:

```java
INVESTIGATION_CLEARANCE(
    GatePolicy.ALWAYS, true,
    List.of(AmlGroups.AML_COMPLIANCE),
    "Investigation clearance on inconclusive evidence — compliance review required"),
```

**`EntityResolutionResult`** — add compact constructor to enforce `riskScore` invariant:

```java
public record EntityResolutionResult(
        String entityId,
        String ownershipChain,
        String entityType,
        double riskScore) {
    public EntityResolutionResult {
        if (riskScore < 0.0 || riskScore > 1.0) {
            throw new IllegalArgumentException(
                "riskScore must be in [0.0, 1.0], got: " + riskScore);
        }
    }
}
```

### Existing types (unchanged)

- `TriageDecision` — `SAR_WARRANTED`, `FALSE_POSITIVE`, `INCONCLUSIVE`
- `PatternAnalysisResult` — `structuringDetected`, `description`
- `SuspiciousTransaction` — `id`, `originAccountId`, `destinationAccountId`, `amount`, `currency`, `timestamp`, `flagReason`

## Evaluator Components (api/)

All components are plain classes in `api/` — no CDI, no framework. Testable with
JUnit 5 alone.

### HardGateEvaluator

Checks absolute gates in priority order. Returns `Optional<TriageResult>`.

| Priority | Gate | Condition | Decision |
|----------|------|-----------|----------|
| 1 | `SANCTIONS_HIT` | `osintScreening.sanctionsHit() == true` | SAR_WARRANTED |
| 2 | `CONFIRMED_PEP` | `osintScreening.pepHit() == true && entityType == "PEP"` | SAR_WARRANTED |
| 3 | `SHELL_COMPANY` | `entityType == "SHELL_COMPANY"` | SAR_WARRANTED |

When a gate fires: `riskScore = 1.0`, the gate enum is set, `cbrThresholdAdjustment = null`,
factors list is empty (gate is the sole reason).

### RiskScorer

Accumulates a weighted risk score from specialist findings. Returns
`ScoringResult(double score, List<RiskFactor> factors)` — an inner record of
`RiskScorer` (return-only type, not a top-level file).

| Factor | Weight | Value |
|--------|--------|-------|
| Entity risk score | 0.35 | `clamp(entityResolution.riskScore(), 0.0, 1.0)` |
| Structuring detected | 0.25 | 1.0 if true, 0.0 if false |
| PEP entity type | 0.20 | 1.0 if entityType is PEP, 0.0 otherwise |
| OSINT declined | 0.10 | 0.5 if declined (uncertainty), 0.0 otherwise |
| PEP hit (OSINT) | 0.10 | 1.0 if pepHit, 0.0 otherwise |

Score formula: `Σ(factor_value × weight)` — always in [0.0, 1.0]. The entity risk
score input is defensively clamped to [0.0, 1.0] before weighting.
`EntityResolutionResult` now enforces this invariant at construction (see Modified
existing types), but the clamp provides defense-in-depth.

OSINT declined gets a partial value (0.5) rather than full — it signals incomplete
information, not confirmed risk.

### CbrAdjuster

Shifts SAR and FALSE_POSITIVE thresholds based on CBR path advice. Both thresholds
move symmetrically toward the predominant historical outcome.

- No adjustment when: `cbrPathAdvice` is null, `caseCount == 0`, `confidence < cbrMinConfidence` (default 0.3), `error == true`, or `predominantOutcome == null`
- Predominant `FALSE_POSITIVE`:
  - SAR threshold raised (harder to file SAR)
  - FP threshold raised (easier to classify as false positive)
- Predominant `SAR_WARRANTED`:
  - SAR threshold lowered (easier to file SAR)
  - FP threshold lowered (harder to classify as false positive)
- Predominant `INCONCLUSIVE`: no adjustment — ambiguous historical outcomes provide
  no directional signal. Thresholds remain at their configured defaults.
- Adjustment magnitude: `confidence × predominantOutcomeFrequency × maxAdjustment`
- Each threshold capped at `±maxCbrAdjustment` (default 0.15) individually

**Threshold ordering invariant:** After adjustment, the CbrAdjuster validates
`adjustedSarThreshold > adjustedFpThreshold`. If violated (misconfigured preferences
or extreme CBR adjustment), both thresholds are clamped to their midpoint ± 0.05:
`mid = (adjustedSar + adjustedFp) / 2; sar = mid + 0.05; fp = mid - 0.05`. This
maintains a minimum 0.10 gap for the INCONCLUSIVE band. The adjuster logs a warning
when clamping activates.

### InvestigationTriageEvaluator

Composes the three components:

```
1. hardGateEvaluator.evaluate(input) → if present, return immediately
2. riskScorer.score(input) → ScoringResult
3. cbrAdjuster.adjust(sarThreshold, fpThreshold, input.cbrPathAdvice())
4. Apply thresholds:
   - score >= adjustedSarThreshold → SAR_WARRANTED
   - score <= adjustedFpThreshold → FALSE_POSITIVE
   - otherwise → INCONCLUSIVE
```

Default thresholds (from preferences):
- SAR threshold: 0.6
- FALSE_POSITIVE threshold: 0.25

## Triage Preferences

**`AmlTriagePolicyKeys`** — follows the `AmlMemoryPolicyKeys` pattern, using
`PreferenceKey<DoublePreference>` (engine-api `io.casehub.api.spi.routing.DoublePreference`)
with `DoublePreference.of(...)` and `DoublePreference::parse`:

| Key | Namespace | Default |
|-----|-----------|---------|
| `SAR_THRESHOLD` | `casehubio.aml.triage` | 0.6 |
| `FALSE_POSITIVE_THRESHOLD` | `casehubio.aml.triage` | 0.25 |
| `MAX_CBR_ADJUSTMENT` | `casehubio.aml.triage` | 0.15 |
| `CBR_MIN_CONFIDENCE` | `casehubio.aml.triage` | 0.3 |

The evaluator components receive resolved threshold values as constructor
parameters (plain doubles). The worker resolves preferences and passes the values
in. This keeps `api/` free of platform preference imports.

## Worker Retrofit — Typed Inputs

Workers that read specialist findings from their input maps get typed deserialization
at the top of their lambda via `objectMapper.convertValue()`.

| Worker | Reads | Deserialization target |
|--------|-------|-----------------------|
| `entityResolutionWorker` | `transaction` | `SuspiciousTransaction` |
| `sarDraftingWorkerJunior` | `transaction`, `entityResolution`, `osintScreening` | Domain records |
| `sarDraftingWorkerSenior` | `transaction`, `entityResolution`, `osintScreening` | Domain records |
| `complianceReviewOpeningWorker` | `transaction`, `entityResolution`, `osintScreening` | Domain records (partially typed already) |
| `InvestigationTriageWorker` | all four findings | `TriageInput` |

Workers that are pure output stubs (`patternAnalysisWorker`, `osintScreeningWorker`,
`osintScreeningWorkerSenior`, `seniorAnalystWorker`) do not need retrofit — they
construct fixed return maps without reading input.

**`InvestigationTriageWorker.create()` signature:**

```java
public static Worker create(ObjectMapper objectMapper, PreferenceProvider preferenceProvider)
```

Uses `WorkerFunction.Sync` (not `FlowWorkerFunction`) because INCONCLUSIVE decisions
declare `PlannedAction(INVESTIGATION_CLEARANCE)`, and FlowWorkerExecutor does not
yet support PlannedAction (engine#564).

Worker behaviour by decision:
- `SAR_WARRANTED` → `WorkerResult.of(resultMap)` — no PlannedAction (MLRO gate is downstream via SAR_FILING)
- `FALSE_POSITIVE` → `WorkerResult.of(resultMap)` — positive analytical conclusion, no gate needed
- `INCONCLUSIVE` → `WorkerResult.of(resultMap, PlannedAction.of(description, AmlActionType.INVESTIGATION_CLEARANCE.actionType(), parameters))` — engine pauses for compliance officer approval before writing triage result to context

INCONCLUSIVE PlannedAction parameters (context for the gate reviewer):

```java
Map.of(
    "riskScore", triageResult.riskScore(),
    "reason", triageResult.reason(),
    "factors", triageResult.factors().stream()
        .map(f -> f.name() + "=" + f.weight())
        .collect(Collectors.joining(", ")))
```

**Gate rejection behaviour:** When the compliance officer rejects the
INVESTIGATION_CLEARANCE gate, the engine marks the capability execution as
completed-rejected. The triage result is NOT committed to context. The triage
binding's condition (`.investigationTriage == null`) remains true, but the engine's
execution tracking prevents re-fire — the capability has already been executed for
this context state. The case remains open with no completion path satisfied,
requiring manual intervention. This is the correct regulatory posture: an
inconclusive case that a compliance officer refuses to clear must not auto-resolve.
The same gate rejection semantics apply to the existing SAR_FILING PlannedAction.

**`AmlInvestigationCaseDescriptor`** gains `PreferenceProvider` as a new constructor
parameter, wired from `AmlInvestigationCaseHub`.

## YAML Bindings

No changes required. The triage worker's input/output field names are preserved:
- Input projection: `entityResolution`, `patternAnalysis`, `osintScreening`, `cbrPathAdvice`
- Output projection: `investigationTriage: .` (entire return map)
- SAR-drafting condition: `.investigationTriage.decision == "SAR_WARRANTED"`
- `investigation-cleared` goal: `decision == "FALSE_POSITIVE" or decision == "INCONCLUSIVE"`

The output is richer (additional fields) but backward-compatible.

## Testing Strategy

### Unit tests (api/) — pure domain, no Quarkus

**`HardGateEvaluatorTest`:**
- Sanctions hit → SAR_WARRANTED regardless of other signals
- PEP with sanctions → SAR_WARRANTED
- Shell company → SAR_WARRANTED
- Gate priority: sanctions checked before PEP
- No gate triggered → empty Optional
- Low-risk INDIVIDUAL with clean OSINT → no gate fires

**`RiskScorerTest`:**
- High entity risk + structuring → high aggregate score
- Low risk, clean OSINT, no PEP → low aggregate score
- OSINT declined contributes partial score (0.5 × weight)
- Score always in [0.0, 1.0]
- Factors list contains all contributing signals

**`CbrAdjusterTest`:**
- Null CBR advice → thresholds unchanged
- Low confidence → thresholds unchanged
- Predominant FALSE_POSITIVE → SAR threshold raised
- Predominant SAR_WARRANTED → SAR threshold lowered
- Adjustment capped at max
- Predominant INCONCLUSIVE → thresholds unchanged
- CBR with error → thresholds unchanged

**`InvestigationTriageEvaluatorTest`:**
- Hard gate fires → immediate return, scorer not consulted
- Score above SAR threshold → SAR_WARRANTED with factors
- Score below FP threshold → FALSE_POSITIVE
- Score in ambiguous band → INCONCLUSIVE
- CBR shifts borderline INCONCLUSIVE → FALSE_POSITIVE
- CBR shifts borderline INCONCLUSIVE → SAR_WARRANTED
- CBR cannot shift a hard-gate decision

### @QuarkusTest integration tests (app/)

**`InvestigationTriageFlowTest`** — retrofit existing + add scenarios:
- SAR path: high risk → SAR_WARRANTED → SAR drafted → compliance review → `investigation-complete`
- FALSE_POSITIVE path: low risk → FALSE_POSITIVE → `investigation-cleared` (no SAR)
- INCONCLUSIVE path: mixed signals → INCONCLUSIVE → gate approval (INVESTIGATION_CLEARANCE) → `investigation-cleared`
- All paths drain to completion (protocol PP-20260604-820c35)
- Verify structured output in case context

## File Inventory

### New files

| File | Module | Description |
|------|--------|-------------|
| `TriageInput.java` | api | Typed aggregate of specialist findings |
| `CbrPathAdvice.java` | api | Typed CBR path advisor output |
| `TriageResult.java` | api | Structured triage output |
| `HardGate.java` | api | Hard gate enum |
| `RiskFactor.java` | api | Contributing factor record |
| `HardGateEvaluator.java` | api | Absolute gate checker |
| `RiskScorer.java` | api | Weighted risk score accumulator |
| `CbrAdjuster.java` | api | CBR threshold adjustment |
| `InvestigationTriageEvaluator.java` | api | Composer of the three components |
| `AmlTriagePolicyKeys.java` | app | Preference key constants |
| `HardGateEvaluatorTest.java` | api (test) | Unit tests |
| `RiskScorerTest.java` | api (test) | Unit tests |
| `CbrAdjusterTest.java` | api (test) | Unit tests |
| `InvestigationTriageEvaluatorTest.java` | api (test) | Unit tests |
| `TriageInputTest.java` | api (test) | Unit tests |

### Modified files

| File | Change |
|------|--------|
| `InvestigationTriageWorker.java` | Replace stub with `WorkerFunction.Sync` + evaluator call + PlannedAction for INCONCLUSIVE |
| `AmlInvestigationCaseDescriptor.java` | Add PreferenceProvider param; retrofit typed inputs in sarDrafting, complianceReview, entityResolution workers; update `new OsintResult(...)` call in buildSummary |
| `AmlInvestigationCaseHub.java` | Wire PreferenceProvider to descriptor |
| `InvestigationTriageFlowTest.java` | Add FALSE_POSITIVE and INCONCLUSIVE test paths |
| `OsintResult.java` | Add `declined` and `reason` fields; remove `detail`; add compact constructor validation |
| `EntityResolutionResult.java` | Add compact constructor with riskScore [0.0, 1.0] validation |
| `AmlActionType.java` | Add `INVESTIGATION_CLEARANCE` constant |
| `InvestigationSummary.java` | Update `OsintResult` type reference |
| `DefaultOsintScreeningService.java` | Update `new OsintResult(...)` constructor call (3-arg → 4-arg) |
| `AmlInvestigationCaseDescriptorTest.java` | Update constructor call for new PreferenceProvider param; move `investigation-triage-agent` from `FLOW_WORKERS` to `SYNC_WORKERS` in execution model classification test |
| `AmlInvestigationCoordinatorTest.java` | Update `new OsintResult(...)` constructor calls (2 sites) |
| `DefaultSarDraftingServiceTest.java` | Update `new OsintResult(...)` constructor call |
| `AmlActionRiskClassifierTest.java` | Add test for `INVESTIGATION_CLEARANCE`: GatePolicy.ALWAYS, candidateGroups=[AML_COMPLIANCE], reversible=true |
