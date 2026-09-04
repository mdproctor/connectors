## D1: Where do labels and types go?

**Choice:** On `@HumanApproval` only
**Alternatives:**
- Both on `@HumanApproval` and `@RequiresQuorum` — duplicates attributes, creates ambiguity when both present
- `types` as separate `@WorkItemTypes` annotation — adds composition complexity for minimal gain
**Rationale:** `@HumanApproval` already owns per-WorkItem configuration (title, priority, candidates, deadlines). Labels and types are per-WorkItem properties. `@RequiresQuorum` stays coordination-only.
**Trade-offs:** Standalone `@RequiresQuorum` (without `@HumanApproval`) cannot set labels/types — must use programmatic path for queue integration in that edge case.
**Sources:** WorkItemCreateRequest.java (labels, types fields), existing annotation definitions
**Exploration:** quick
**Status:** captured

## D2: Queue-integrated example structure

**Choice:** Single combined example module (`queue-integrated-annotated`)
**Alternatives:**
- Two separate examples (direct labels vs rule inference) — separates concerns but fragments understanding
**Rationale:** Direct labels and label-rule inference are complementary paths best understood together. One example showing both demonstrates the full queue integration story.
**Trade-offs:** Slightly more complex single example vs simpler but disconnected pair.
**Sources:** queues-examples/ scenarios (FinanceApprovalScenario pattern), eidos examples/README.md structure
**Exploration:** quick
**Status:** captured
