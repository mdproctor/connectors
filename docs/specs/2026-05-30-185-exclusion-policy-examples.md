# Spec: ExclusionPolicy custom implementations — docs + examples (#185)

**Date:** 2026-05-30 (v2)
**Branch:** issue-235-sxs-sweep
**Scale:** S · Low

## Scope

Document the `ExclusionPolicy` SPI extension pattern and provide a concrete runnable demo.
LDAP/group-level exclusion is already handled by `TemplateExpander + GroupMembershipProvider`
(#184) — not covered here. Focus: time-window expiring exclusion as the representative
non-trivial use case.

This scenario demonstrates the **policy contract** for developers implementing custom
`ExclusionPolicy`. It does not drive through `WorkItemService.claim()` — service-tier
integration (audit trail via `BlockedAttemptAuditService`) is covered by `WorkItemServiceTest`.
The scenario is intentionally named `ExclusionPolicyDemoScenario` to signal this distinction.

## Changes

### 1. `ExclusionPolicy` javadoc update

Replace the vague "see #185" forward reference with:

- **Activation**: declare `@Alternative @Priority(1) @ApplicationScoped` on your implementation — CDI replaces `CommaSeparatedExclusionPolicy`
- **`excludedUsers` format**: opaque to the platform — the implementation owns the encoding. The default uses CSV IDs; custom implementations may encode richer metadata. Implementations that replace the default **must handle or reject** the plain CSV format already stored in existing WorkItems.
- **Group-level exclusion**: handled separately by `TemplateExpander + GroupMembershipProvider` (#184); `ExclusionPolicy.check()` operates on individual actor IDs at claim/delegate time
- **Service-tier enforcement**: denials are audited via `BlockedAttemptAuditService` in a `REQUIRES_NEW` transaction — the `reason` string from the denied `PolicyDecision` flows into the audit entry detail field
- `@see CommaSeparatedExclusionPolicy`

### 2. `CommaSeparatedExclusionPolicy` javadoc

Add one sentence: "To replace this with custom logic, declare `@Alternative @Priority(1) @ApplicationScoped` on your implementation."

### 3. `ExpiringExclusionPolicy` — new class in `examples/exclusion/`

Time-window conflict-of-interest exclusion. `excludedUsers` encodes
`userId:YYYY-MM-DD` entries (comma-separated). The policy denies if the actor appears in
the list AND today is before the expiry date.

**Constructor injection of `Clock`** for testability:

```java
@Inject
ExpiringExclusionPolicy() { this(Clock.systemDefaultZone()); }

ExpiringExclusionPolicy(Clock clock) { this.clock = clock; }
```

**Parse semantics** — specified for every branch:

| Token form | Today vs expiry | Decision | Reason |
|---|---|---|---|
| `userId:YYYY-MM-DD` | today < expiry | DENY | `"user 'X' excluded until YYYY-MM-DD (conflict-of-interest cooling-off)"` |
| `userId:YYYY-MM-DD` | today >= expiry | ALLOW | — |
| `userId` (no colon) | N/A | DENY | `"user 'X' in exclusion list"` |
| `userId:not-a-date` | N/A | DENY | `"user 'X' excluded (invalid expiry format — treating as permanent)"` |
| `excludedUsers` null/blank | N/A | ALLOW | — |

Plain-ID (no colon) means excluded permanently — backward-compatible with the CSV format
`CommaSeparatedExclusionPolicy` uses. Tokens with unparseable dates fail safe (deny).

NOT annotated `@Alternative @Priority(1)` in the examples module — would override the
default for all scenarios. Javadoc shows how to activate in a production module.

**Note on TemplateExpander interaction**: if `TemplateExpander` resolves groups and stores
plain `alice,bob` CSV in `excludedUsers`, then switching to `ExpiringExclusionPolicy` treats
those users as permanently excluded (no expiry). This is the correct semantic for existing
data but operators should be aware that adding expiry dates requires updating `excludedUsers`
on affected WorkItems and templates.

### 4. `ExclusionPolicyDemoScenario` + `ExclusionPolicyDemoResponse` — `examples/exclusion/`

REST `POST /examples/exclusion-policy/run`. Demonstrates `ExpiringExclusionPolicy` contract
by calling `check()` directly on a Clock-injected instance. Named `Demo` to signal this is
a policy behavior demonstration, not a full service-tier integration example.

**Steps and structured response:**

```java
public record CheckResult(String actor, String exclusionData, boolean denied, String reason) {}
public record ExclusionPolicyDemoResponse(String scenario, List<CheckResult> results) {}
```

| Step | Actor | `excludedUsers` value | Expected |
|---|---|---|---|
| 1 | alice | `"alice:2099-01-01"` | DENY |
| 2 | alice | `"alice:2020-01-01"` (expired) | ALLOW |
| 3 | bob | `"alice:2099-01-01"` | ALLOW |
| 4 | alice | `null` | ALLOW |
| 5 | alice | `"alice"` (plain, no date) | DENY |
| 6 | alice | `"alice:not-a-date"` | DENY |

Response carries `CheckResult` per step with `denied` boolean and `reason` string.

### 5. No changes to `WorkItemCreateRequest`, `WorkItemService`, or `WorkItemAssignmentService`

This issue is purely additive — javadoc and examples only.

## Testing

**`ExpiringExclusionPolicyTest`** (pure unit, uses `Clock.fixed()`):
- Future date → DENY with correct reason containing the date
- Past date → ALLOW
- Exact-boundary (today == expiry) → ALLOW (not strictly before)
- Non-listed user → ALLOW
- Null exclusion → ALLOW
- Plain ID (no colon) → DENY with reason not containing "until"
- Invalid date token → DENY with reason containing "invalid expiry format"

**`ExclusionPolicyDemoScenarioTest`** (@QuarkusTest):
- `POST /examples/exclusion-policy/run` → 200
- `results[0].denied == true`, `results[1].denied == false`
- All 6 results have expected `denied` values as per table above
- Deterministic: no date-sensitive assertions — future dates use 2099, past use 2020
