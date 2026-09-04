---
layout: post
title: "The Thirteen Fields That Weren't There"
date: 2026-06-21
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [mongodb, persistence, occ, data-loss]
series: issue-270-mongo-document-field-sync
---

MongoWorkItemDocument has been shipping since Chapter C20 with a silent data loss bug: 13 of the 44 WorkItem fields weren't mapped in `from()` or `toDomain()`. Put a WorkItem through the MongoDB store, get it back, and `callerRef`, `parentId`, `templateId`, `confidenceScore`, `outcome`, and eight others come back null. The JPA store round-trips everything because Hibernate maps the entity directly. MongoDB doesn't — you write the mapping by hand, and nobody wrote it for the fields added after C20.

The immediate trigger was #240 (lifecycle alignment), which added `percentComplete` and `statusNote`. Those got mapped. But during post-merge cleanup I noticed the issue body for #270 listing thirteen fields that predated #240 and were never there. Claim SLA tracking (`accumulatedUnclaimedSeconds`, `lastReturnedToPoolAt`), spawn routing (`callerRef`, `parentId`), named outcomes (`templateId`, `permittedOutcomes`, `excludedUsers`, `outcome`), schema validation (`inputDataSchema`, `outputDataSchema`), AI metadata (`confidenceScore`), and scope — all silently dropped on every round-trip.

The fix is mechanical: add the fields, map them in both directions, test. But the spec review surfaced two things that made it worth designing properly rather than just slapping fields on.

First, `MongoWorkItemStore.put()` had no optimistic concurrency control. The JPA store uses `@Version` — two nodes racing to claim the same WorkItem produces `OptimisticLockException` → HTTP 409. The MongoDB store used `persistOrUpdate()`, an unconditional upsert. Both claims succeed. Since `version` was one of the thirteen missing fields, adding it as a plain round-trip value would be a missed opportunity. We implemented OCC using `replaceOne` with a version filter — different from the `findOneAndUpdate` pattern in SpawnGroupStore and ScheduleStore, but deliberately so. Those stores have ~12 fields and atomically increment counters. WorkItem has 44 fields and no counters. `replaceOne` reuses the existing `from()` method for the full document and self-maintains when fields are added.

Second, the spec review caught that `countByParentAndAssignee` was going to inherit the JPA store's bug: a hardcoded list of 4 terminal statuses when there are now 7. The MongoDB implementation uses `WorkItemStatus.isTerminal()` to derive the exclusion set at runtime. Filed the JPA bug as #271.

We also found that `buildFilter()` silently ignored the `outcome` query dimension — 11 of 12 handled, one dropped. The REST API `GET /workitems?outcome=approved` returned everything. Fixed and tested.

Five default `WorkItemStore` methods that fell back to linear-scanning `scanAll()` now have proper MongoDB server-side filtered queries: `findByCallerRef`, `findByParentId`, `findByParentIdExcludingStatuses`, `findByParentIdWithStatuses`, and `countByParentAndAssignee`. Before this, they "worked" via the defaults but only because the missing fields meant they always returned empty results. Now they both map the fields and query them efficiently.

The migration story is pleasantly simple. MongoDB is schema-less — old documents don't have the 13 new fields, new ones do. The BSON POJO codec leaves absent primitives at Java defaults (0 for `accumulatedUnclaimedSeconds`) and absent objects at null. The OCC version field uses a filter of `{version: null}` for migrating documents, which correctly matches absent fields. First update migrates the document to version 1. No collection-wide migration needed.

This is the kind of bug that stays quiet until the MongoDB backend gets real use. Every test runs against the in-memory or JPA store by default. The persistence-mongodb module has its own @QuarkusTest suite, but none of the roundtrip tests checked these 13 fields — they checked the fields that existed at C20 delivery. The gap accumulated silently as features were added to the JPA entity.
