---
layout: post
title: "Aligning with the SDK — storage, expressions, and a hidden test class"
date: 2026-04-18
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [architecture, testing, tamboui]
excerpt: "Studying the SWF SDK storage abstraction confirms that WorkItemRepository needs multi-field inbox queries — Map<String, byte[]> doesn't express them cleanly — so a richer query object replaces a direct SWF port."
---

There were three threads this session. They don't share a clean narrative arc, so I won't
force one on them. What they have in common is alignment — against the CNCF Serverless
Workflow SDK, against Tamboui's published APIs, and against what was actually in the
repository versus what the documentation said.

## The storage SPI — going KV-native

The `WorkItemRepository` interface had a problem I'd been aware of for a while: it was
SQL-shaped. Seven methods, each encoding a specific query pattern — `findInbox`, `findExpired`,
`findByLabelPattern`, `findUnclaimedPastDeadline`. Fine with one backend. A constraint with two.

I looked at how the SWF SDK handles this. Their persistence layer reduces storage to a
`Map<String, V>` abstraction via `BigMapInstanceTransaction<V,T,S,A>`. Different backends
override the map; the operations are the same.

The SWF pattern is elegant for their use case — workflow state is write-heavy, read on
recovery, keyed by a single ID. WorkItems need multi-field inbox queries. `Map<String, byte[]>`
doesn't express `findInbox(assignee, candidateGroups, status, priority, category, followUp)`
cleanly. So we didn't adopt the SWF pattern wholesale.

What we did adopt: the naming and the philosophy. `WorkItemRepository` became `WorkItemStore`.
`save()` became `put()`. `findById()` became `get()`. All the individual query methods collapsed
into `scan(WorkItemQuery)`, where `WorkItemQuery` is a value object with static factories that
capture intent:

```java
WorkItemQuery.inbox("alice", List.of("finance-team"), null)
WorkItemQuery.expired(Instant.now())
WorkItemQuery.claimExpired(Instant.now())
WorkItemQuery.byLabelPattern("legal/**")
WorkItemQuery.all()
```

The JPA implementation translates these to dynamic JPQL. A future MongoDB or Redis backend
translates them to aggregation pipelines or set operations. The SPI no longer assumes SQL.

## Expressions — partial alignment

The `FilterConditionEvaluator` interface took two separate string parameters — `language`
and `expression`. You could accidentally call a JEXL evaluator with a JQ expression and
get a silent false rather than an error.

The SWF SDK bundles these into `ExpressionDescriptor`. We did the same.
`FilterConditionEvaluator` became `WorkItemExpressionEvaluator`, and `evaluate(WorkItem, String)`
became `evaluate(WorkItem, ExpressionDescriptor)`. Language and expression travel together —
the pairing is enforced at the type level.

We kept our string-keyed registry rather than adopting SWF's priority-based dispatch. Three
known evaluators don't need a priority system.

## An orthogonal break — the ledger supplement API

While running the full test suite, the ledger module failed to compile. The `quarkus-ledger`
sibling project had updated its API: fields that used to be direct on `LedgerEntry` (rationale,
planRef, decisionContext, sourceEntityId) moved into supplement objects.

The new pattern:

```java
final var compliance = new ComplianceSupplement();
compliance.rationale = event.rationale();
compliance.planRef = event.planRef();
entry.attach(compliance);
```

Reading back is via typed accessors: `e.compliance().map(c -> c.planRef).orElse(null)`. It's
a cleaner design — supplements are composable and don't bloat the base class. Worth the
migration cost.

## TestBackend — not where you'd expect

The Tamboui Pilot test harness (`TuiTestRunner`) lives in `tamboui-tui:test-fixtures`. The class
it depends on — `TestBackend` — is published in `tamboui-core:test-fixtures`. The documentation
mentions only the former.

Once both are declared as test dependencies, all 6 Pilot tests pass headlessly —
`pilot.press('s')` steps through the document review scenario, queue labels update, CDI bean
state confirms the transitions. No real terminal required.

The architectural question is whether `TestBackend` belongs in `tamboui-core` (where its
interface lives) or in `tamboui-tui` (where its only consumer lives). I've raised it with
Max Anderston. Locality wins for consumers; interface proximity wins for maintainers.
Either way, the cross-module dependency should be declared transitively so Maven picks it
up automatically.
