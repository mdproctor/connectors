## D1: General create type, not HumanTask-specific

**Choice:** New CloudEvent type `io.casehub.work.workitem.create` — data maps to `WorkItemCreateRequest` fields. Any system can use it (engine, Qhorus, external).
**Alternatives:**
- HumanTask-specific type (`io.casehub.engine.humantask.requested`) — too narrow, would need a second general type later
- Extend existing REQUESTED type (make `templateid` optional) — breaks existing contract, mixes template-only and full-request semantics
**Rationale:** `WorkItemCreateRequest` already carries all HumanTask fields generically. Nothing about WorkItem creation is engine-specific — the engine-specific concerns (BlackboardRegistry lookup, PlanItem validation) belong on the engine side.
**Trade-offs:** External callers see the full `WorkItemCreateRequest` surface area rather than a curated subset. Acceptable at pre-release.
**Sources:** `HumanTaskScheduleHandler.java` (lines 129-253 — engine context flows through WorkItemCreateRequest fields, not engine types), `WorkItemCreateRequest.java`, `WorkCloudEventInboundAdapter.java`
**Exploration:** quick
**Status:** captured

## D2: callerRef-based idempotency

**Choice:** Idempotency key is `callerRef` from the data payload. Falls back to `ce.getId()` if callerRef absent.
**Alternatives:**
- CloudEvent `id` only — transport-level dedup misses domain-level duplicates
- Both independently — adds a second lookup for marginal benefit
**Rationale:** Consistent with how REQUESTED works (where `ce.getId()` IS the callerRef). Callers control correlation semantics — engine uses `case:{caseId}/pi:{planItemId}`, Qhorus uses `qhorus:{channelId}/...`, simple callers omit it and get `ce.getId()`.
**Trade-offs:** Callers with the same callerRef pattern from different systems could collide. Mitigated by callerRef conventions encoding the source system.
**Sources:** `WorkCloudEventInboundAdapter.processInTenantContext()` (line 61 — `findByCallerRef(ce.getId())`), `PlanItemCallerRef.encode()`, `WorkCloudEventTypes`
**Exploration:** quick
**Status:** captured
