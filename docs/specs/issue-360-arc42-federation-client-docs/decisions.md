## D1: Combined vs separate layer entries

**Choice:** Single combined layer entry "L8 Federation" covering both federation/ and client/
**Alternatives:**
- Separate entries (L8 Federation + L9 Client) — gives client its own visibility, but it's one class and exists to serve federation
**Rationale:** client/ has one class (WorkItemClient), exists primarily for FederationProxy, and its CDI/JPA independence is a design decision within the federation story, not a separate concern
**Trade-offs:** Standalone consumers of client/ may not find it via its own layer entry; mitigated by Module Index table entry
**Sources:** ARC42STORIES.MD §9.4 L7 pattern (six modules in one entry), MODULES.md federation/ and client/ entries
**Exploration:** quick
**Status:** captured

## D2: Chapter assignment

**Choice:** No existing chapter participation; reference issue lineage (#92, #95) instead
**Alternatives:**
- Assign to future C37 — premature since federation hasn't merged to main yet
- Assign to C27 (Distributed SSE) — wrong scope, C27 is same-cluster LISTEN/NOTIFY
**Rationale:** Federation code lives on issue-92-distributed-workitems branch, predates chapter-based delivery. No existing chapter covers its construction.
**Trade-offs:** Layer × Chapter matrix will show all dashes for L8; acceptable since chapter creation is out of scope for this issue
**Sources:** ARC42STORIES.MD §9.2 Chapter Index (C1–C36), issue #360 body
**Exploration:** quick
**Status:** captured
