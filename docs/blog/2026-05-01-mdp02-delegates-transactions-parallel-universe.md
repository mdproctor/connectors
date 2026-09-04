---
layout: post
title: "Delegates, transactions, and not building a parallel universe"
date: 2026-05-01
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [postgresql, cdi, vertx, mutiny, github, testing]
excerpt: "castTo(PgConnection.class) fails immediately because Mutiny codegen produces sibling hierarchies, not parent-child — the subscription needs the raw Vert.x delegate to call notificationHandler()."
---

The decision to use PostgreSQL LISTEN/NOTIFY for distributed SSE was already
made — the April 29 entry covers it. This is about what we found when we
actually built it.

## The cast that couldn't

The subscriber connection needs to call `notificationHandler()`, which lives on
`io.vertx.mutiny.pgclient.PgConnection`. The pool gives you back a connection.
The obvious move:

```java
pool.getConnection()
    .onItem().castTo(PgConnection.class)
    .subscribe().with(...)
```

`ClassCastException`. Immediate. The Mutiny codegen generates flat wrapper
hierarchies — `SqlConnection` and `PgConnection` are sibling classes, not
parent and child. The underlying delegate IS a `PgConnection`, but the
Mutiny wrapper doesn't know that. Claude caught the distinction and found the
path out: unwrap the delegate, re-wrap it.

```java
pool.getConnection().subscribe().with(conn -> {
    final io.vertx.pgclient.PgConnection pg =
            (io.vertx.pgclient.PgConnection) conn.getDelegate();
    final PgConnection pgConn = PgConnection.newInstance(pg);
    pgConn.notificationHandler(n -> handleNotification(n.getPayload()));
    pgConn.query("LISTEN " + CHANNEL).execute().subscribe().with(...);
});
```

Not intuitive. Not documented. Worth writing down.

## AFTER_SUCCESS without a transaction

The broadcaster observes lifecycle events with `AFTER_SUCCESS` — the
pg_notify only fires after the JTA transaction commits, so rolled-back events
don't leak into the channel. The right call.

But it created a test problem. CDI's `AFTER_SUCCESS` observers are silently
skipped when there's no active JTA transaction. Fire the event outside a
transaction in a test, and the observer never runs — no exception, no warning,
just nothing delivered. We needed explicit `UserTransaction.begin()/commit()`
wrappers in the integration tests to give the observer something to respond to.

## Not building a parallel universe

One concern I had going into the GitHub issue tracker work: engineers who live
in GitHub Issues should not feel like they're also maintaining a shadow system.
The module already creates issues and auto-closes them on completion. But if a
WorkItem changes priority, gets delegated, or is suspended — none of that was
reflected in GitHub. The issue just sat there looking stale.

We added `syncToIssue()` as a default no-op to the `IssueTrackerProvider` SPI —
existing custom providers don't break. The GitHub implementation does the work:
GET the current labels, replace the managed namespaces (`priority:*`,
`category:*`, `status:*`) without touching anything the user added, set issue
state open or closed based on WorkItem status, create missing labels on first use.

The result is that the GitHub Issue reflects the WorkItem lifecycle in real
time. An engineer sees `priority:critical`, `status:in-progress`,
`category:finance` without leaving GitHub. The next step is incoming: GitHub
Issue close drives WorkItem completion, not just the other way around. That
needs webhook infrastructure and identity mapping to do right, so it's a
separate piece.
