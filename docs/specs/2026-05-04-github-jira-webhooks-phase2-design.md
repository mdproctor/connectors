# GitHub + Jira Inbound Webhooks — Phase 2 Design
**Date:** 2026-05-04
**Issue:** #156 Phase 2
**Prerequisites:** #160 (WorkItemPriority rename: CRITICAL→URGENT, NORMAL→MEDIUM)

---

## Goal

Close the bidirectional loop with GitHub and Jira. Phase 1 syncs WorkItem state outbound
to the tracker. Phase 2 receives tracker events inbound and drives WorkItem transitions —
making GitHub Issues and Jira a real control surface for WorkItems.

---

## Normative Alignment

All inbound events are translated to normative speech acts before driving WorkItem
transitions. Tracker-specific vocabulary never leaks past the provider boundary.

### NormativeResolution (new enum — `casehub-work-api`)

```java
public enum NormativeResolution { DONE, DECLINE, FAILURE }
```

| NormativeResolution | Meaning | WorkItem transition |
|---|---|---|
| DONE | Work fulfilled — "completed" | COMPLETED |
| DECLINE | Work refused — "won't do" | CANCELLED |
| FAILURE | Work attempted, could not complete | REJECTED |

### Close event mapping

| NormativeResolution | GitHub | Jira |
|---|---|---|
| DONE | `state_reason: completed` or null (legacy) | Resolution: Done, Fixed |
| DECLINE | `state_reason: not_planned` | Resolution: Won't Do, Won't Fix, Duplicate |
| FAILURE | *(no equivalent)* | Resolution: Cannot Reproduce, Incomplete |

GitHub has no FAILURE concept — asymmetry is acceptable and documented.

---

## Priority Alignment (after #160)

WorkItemPriority aligns with Linear: **URGENT / HIGH / MEDIUM / LOW**.

### Inbound priority mapping

**GitHub labels (created by Phase 1 outbound sync — round-trip exact):**

| GitHub label | WorkItemPriority |
|---|---|
| `priority:urgent` | URGENT |
| `priority:high` | HIGH |
| `priority:medium` | MEDIUM |
| `priority:low` | LOW |

**Jira native priority field:**

| Jira priority | WorkItemPriority |
|---|---|
| Highest | URGENT |
| High | HIGH |
| Medium | MEDIUM |
| Low | LOW |
| Lowest | LOW |

---

## Full Inbound Event Mapping

### Close / terminal

| Event | GitHub | Jira | WorkItem transition |
|---|---|---|---|
| Work done | `action: closed`, `state_reason: completed` | Status closed, resolution Done/Fixed | complete() → COMPLETED |
| Work declined | `action: closed`, `state_reason: not_planned` | Resolution Won't Do/Won't Fix/Duplicate | cancel() → CANCELLED |
| Work failed | *(none)* | Resolution Cannot Reproduce/Incomplete | reject() → REJECTED |

### Assignment

| Event | GitHub | Jira | WorkItem transition |
|---|---|---|---|
| Assigned | `action: assigned` | changelog: `assignee` set | assign() |
| Unassigned | `action: unassigned` | changelog: `assignee` → null | release() → PENDING |

### Content sync

| Field | GitHub | Jira | WorkItem field |
|---|---|---|---|
| Title | `action: edited`, title changed | changelog: `summary` | `title` |
| Description | `action: edited`, body changed | changelog: `description` | `description` (footer stripped) |

Description inbound: strip the `*Linked WorkItem: \`{id}\`*` footer appended by Phase 1
`createIssue` before writing to `WorkItem.description`.

### Priority and labels

| Event | GitHub | Jira | WorkItem transition |
|---|---|---|---|
| Priority set | `action: labeled`, `priority:*` label | changelog: `priority` field | update priority |
| Label added | `action: labeled`, non-managed label | changelog: `labels` added | add label |
| Label removed | `action: unlabeled`, non-managed label | changelog: `labels` removed | remove label |

Managed labels (`priority:*`, `status:*`, `category:*`) are skipped on inbound label events
— they are owned by Phase 1 outbound sync and must not be echoed back as WorkItem labels.

### Explicit non-mappings (ignored, return 200)

| Event | GitHub | Jira | Reason |
|---|---|---|---|
| Reopened | `action: reopened` | Status back to Open | WorkItem terminal states are final |
| Milestoned | `action: milestoned` | Fix Version set | No WorkItem equivalent |
| Comment | `issue_comment` event | `comment_created` | GitHub/Jira is source of truth for discussion |

---

## Architecture — Option A (chosen)

Provider parses raw payload into a normalised `WebhookEvent`. A shared
`WebhookEventHandler` drives the WorkItem transition. Tracker vocabulary stays
inside the provider.

```
POST /workitems/github-webhook
POST /workitems/jira-webhook
        │
        ▼
  HMAC verification (inline, constant-time)
        │
        ▼
  provider.parseWebhookEvent(headers, body) → WebhookEvent
        │
        ▼
  WebhookEventHandler.handle(WebhookEvent)
        │
        ├── WorkItemIssueLink.findByRef(trackerType, externalRef)
        └── WorkItemService.complete() / cancel() / reject() / assign() / release() / update fields
```

---

## New Types

### `WebhookEvent` (record — `casehub-work-issue-tracker`)

```java
public record WebhookEvent(
    String trackerType,
    String externalRef,
    WebhookEventKind eventKind,
    String actor,
    NormativeResolution normativeResolution, // CLOSED only
    WorkItemPriority newPriority,            // PRIORITY_CHANGED only
    String labelValue,                       // LABEL_ADDED/REMOVED only
    String newTitle,                         // TITLE_CHANGED only
    String newDescription,                   // DESCRIPTION_CHANGED only
    String newAssignee                       // ASSIGNED only
) {}

public enum WebhookEventKind {
    CLOSED,
    ASSIGNED, UNASSIGNED,
    TITLE_CHANGED, DESCRIPTION_CHANGED,
    PRIORITY_CHANGED,
    LABEL_ADDED, LABEL_REMOVED
}
```

### `IssueTrackerProvider` — new method

```java
/**
 * Parse a raw inbound webhook payload into a normalised WebhookEvent.
 * Default: throws UnsupportedOperationException — providers that do not
 * support inbound webhooks need not implement this.
 */
default WebhookEvent parseWebhookEvent(Map<String, String> headers, String body) {
    throw new UnsupportedOperationException(
        trackerType() + " does not support inbound webhooks");
}
```

---

## REST Endpoints

### `GitHubWebhookResource` — `POST /workitems/github-webhook`

- Header: `X-Hub-Signature-256: sha256=<hmac>`
- HMAC: `HMAC-SHA256(webhookSecret, rawBody)`, constant-time compare
- Config: `casehub.work.issue-tracker.github.webhook-secret` (new, `Optional<String>`)
- No secret configured → 401 (fail closed)
- Invalid HMAC → 401
- Unknown action → 200, no transition
- Processing error → log + 200 (prevent GitHub retries)

### `JiraWebhookResource` — `POST /workitems/jira-webhook`

- Header: `X-Hub-Signature: sha256=<hmac>`
- HMAC: same algorithm
- Config: `casehub.work.issue-tracker.jira.webhook-secret` (new)
- Same error handling as GitHub

### New `JiraIssueTrackerConfig`

```
casehub.work.issue-tracker.jira.webhook-secret  — Optional<String>
casehub.work.issue-tracker.jira.token           — Optional<String>
casehub.work.issue-tracker.jira.base-url        — Optional<String>
```

---

## `WebhookEventHandler`

Shared `@ApplicationScoped` service. Both endpoints call it after HMAC + parse.

**Lookup:** `WorkItemIssueLink.findByTrackerRef(trackerType, externalRef)` — new
finder returning `List<WorkItemIssueLink>` (without workItemId, since the webhook
arrives without it). May return multiple links if the same issue is linked to more
than one WorkItem. Each gets the transition applied independently.

**Rules:**
1. Terminal WorkItems (COMPLETED/CANCELLED/REJECTED) — skip silently, return 200
2. Transition failures — log warning, do not propagate (prevent tracker retries)

**Transition map:**

| WebhookEventKind | Action |
|---|---|
| CLOSED + DONE | `workItemService.complete(id, actor, null)` |
| CLOSED + DECLINE | `workItemService.cancel(id, actor)` |
| CLOSED + FAILURE | `workItemService.reject(id, actor, null)` |
| ASSIGNED | `workItemService.assign(id, newAssignee)` |
| UNASSIGNED | `workItemService.release(id)` |
| TITLE_CHANGED | `workItem.title = newTitle` (direct field update) |
| DESCRIPTION_CHANGED | strip footer, `workItem.description = newDescription` |
| PRIORITY_CHANGED | `workItem.priority = newPriority` |
| LABEL_ADDED | add to `workItem.labels` |
| LABEL_REMOVED | remove from `workItem.labels` |

---

## Flyway

No new migrations required for Phase 2 itself. `WorkItemIssueLink` is unchanged.
`NormativeResolution` and `WebhookEventKind` are in-memory only.

Phase 2 ships after #160 (priority rename) which adds V5001.

---

## Testing Strategy

**Unit — `WebhookEventHandlerTest`** (~15 tests)
Direct calls with stubbed `WorkItemService` and pre-built `WebhookEvent` records.
One test per eventKind × normative outcome. Covers terminal skip, unknown externalRef,
transition failure swallowed.

**Unit — `GitHubWebhookParserTest` / `JiraWebhookParserTest`** (~12 tests)
`parseWebhookEvent()` in isolation with real fixture JSON payloads.
Verifies correct `WebhookEvent` fields for each event type.

**`@QuarkusTest` — `GitHubWebhookResourceTest` / `JiraWebhookResourceTest`** (~12 tests)
REST Assured against full endpoints. Covers: valid HMAC → 200 + transition,
invalid HMAC → 401, unknown action → 200, no secret → 401, terminal WorkItem → 200.
Fixture payloads in `src/test/resources/fixtures/github/` and `.../jira/`.

**Total: ~35–40 tests. H2 only — no Testcontainers needed.**

---

## Out of Scope (Phase 2)

- Identity mapping (assigneeId ↔ GitHub login / Jira username) — Phase 3 (#156)
- Two-way label sync beyond what Phase 1 + Phase 2 already cover — Phase 4 (#156)
- `JiraIssueTrackerProvider.fetchIssue()` / `createIssue()` — Jira outbound sync is a separate effort; Phase 2 only adds inbound webhook parsing for Jira
- Webhook registration automation (registering the webhook URL with GitHub/Jira via API)

---

## Prerequisites

1. **#160** — WorkItemPriority rename (CRITICAL→URGENT, NORMAL→MEDIUM) must land first
2. **#159** — Normative alignment docs (can land in parallel)
