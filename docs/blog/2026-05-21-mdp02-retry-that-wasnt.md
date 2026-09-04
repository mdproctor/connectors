---
layout: post
title: "The retry that wasn't"
date: 2026-05-21
type: phase-update
entry_type: note
subtype: log
projects: [casehub-work]
tags: [round-robin, occ, cdi, backlog, tier-1]
---

The backlog is clear. Everything filed as S or M this session is done — six issues
including one I'd underestimated. That felt satisfying until the code reviewer
flagged a bug I hadn't suspected.

## The retry that was silently doing nothing

`RoundRobinStrategy` needs a persistent cursor — a database row tracking the
last-assigned index per candidate pool. The JPA store updates it with OCC via
`@Version`, and on conflict it retries once before falling back to index 0. Except
it didn't.

The original implementation annotated `acquireNext()` with `@Transactional(REQUIRES_NEW)`
and retried by recursively calling a private `tryAcquire()` method. The logic looked
right. The tests passed. The problem: that recursive call never went through the CDI
proxy — it was a direct `this.method()` invocation. The retry ran inside the
already-poisoned `REQUIRES_NEW` transaction, which was marked rollback-only. Any JPA
operation immediately re-threw or returned stale data. The "retry" was dead on
arrival.

The tests passed because they ran single-threaded. No concurrent OCC race ever
fired. The bug would only appear under production load.

The fix is self-injection:

```java
@Inject
JpaRoutingCursorStore self; // CDI proxy

public int acquireNext(String poolHash, int poolSize) {
    try {
        return self.doAcquire(poolHash, poolSize); // goes through proxy → fresh REQUIRES_NEW
    } catch (PersistenceException e) {
        try {
            return self.doAcquire(poolHash, poolSize);
        } catch (PersistenceException e2) {
            return 0; // fallback
        }
    }
}

@Transactional(Transactional.TxType.REQUIRES_NEW)
public int doAcquire(String poolHash, int poolSize) { ... }
```

Claude caught this in code review — I wouldn't have spotted it without running under
load. It's now in the garden (GE-20260521-0e0122).

## LabelPersistence and when to move things to Tier 1

`WorkItemCreateRequest.labels` was typed as `List<WorkItemLabelResponse>` — an HTTP
response type leaking into a service command. The fix introduced `WorkItemLabelRequest`
and the question became: where should `LabelPersistence` (the MANUAL/INFERRED enum) live?

I initially said: leave it in runtime, only move it when a concrete Tier 1 consumer
appears. That's a cost argument dressed up as architectural reasoning. `LabelPersistence`
is domain vocabulary — MANUAL vs INFERRED is a core concept — and domain vocabulary
belongs in Tier 1 alongside `WorkEventType` and `Outcome`, full stop. We moved it.
Touching 34 files via IntelliJ semantic move, all updated automatically.

## Closing a cherry-picked branch

`epic-excluded-users` had been on the branch list for weeks. `git branch --merged`
said it wasn't merged. That's technically correct — it was integrated via cherry-pick,
which replays commits with new SHAs, leaving the original branch ancestry outside
main's graph.

Verifying it correctly required diffing in the branch→main direction:
`git diff epic-excluded-users main -- '*.java'`. If main has additions the branch
lacks, main is ahead — the code is there in an evolved form. We ran work-end, confirmed
the journal merge was a no-op (DESIGN.md was already more evolved than the journal said),
and marked it closed.

The rest of the session was mechanical — six issues closed, 33 commits squashed to 14
clean commits, and the whole thing pushed upstream to `casehubio/work`. The backlog
that had been accumulating since the builder refactor is gone.
