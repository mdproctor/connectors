---
layout: post
title: "Optional by design, and a PostgreSQL test that told the truth"
date: 2026-04-28
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work]
tags: [sla-reporting, postgresql, testcontainers, quarkus-augmentation, hql]
excerpt: "The PostgreSQL Testcontainer test finds a HQL date-truncation query that passes on H2 but fails on PostgreSQL — catching a dialect incompatibility that would have reached production undetected."
---

Reporting for `quarkus-work` is done. Four endpoints: SLA breach list, actor performance, throughput over time, queue health. The interesting part isn't the endpoints — it's what it took to get a PostgreSQL dialect test passing, and what that test found when it finally ran.

## Keeping the core thin

The first question was where reporting code lives.

It could go into `runtime/`. Faster to build, and wrong. I've watched extensions bloat this way — one "lightweight" analytics dependency becomes Caffeine on every user's classpath, a REST endpoint nobody asked for, a CDI bean that inflates native image startup. `quarkus-work-notifications`, `quarkus-work-queues`, `quarkus-work-ai` are all separate modules for exactly this reason.

`quarkus-work-reports` is a Jandex library module. Add it to your pom and four REST endpoints appear. Leave it out and nothing changes. The runtime dependency on `quarkus-work` gives it JPA entity access without any shared state.

## Throughput without native SQL

Grouping created and completed WorkItems by day, week, or month required a choice: push grouping to the database, or compute in Java.

Claude and I went with Hibernate 6 HQL and pushed the day-level bucketing down:

```java
"SELECT CAST(date_trunc('day', w.createdAt) AS LocalDate), COUNT(w) " +
"FROM WorkItem w WHERE w.createdAt >= :from AND w.createdAt <= :to " +
"GROUP BY CAST(date_trunc('day', w.createdAt) AS LocalDate)"
```

The `CAST AS LocalDate` matters. Without it, `date_trunc`'s return type is dialect-dependent — H2 and PostgreSQL disagree on whether they hand back `LocalDateTime`, `Instant`, or `OffsetDateTime`. The explicit Hibernate Java type cast produces `java.time.LocalDate` regardless. Week and month rollups then happen in Java from those day results — at most 365 rows for a year's worth of data.

## The test that took all afternoon

We wanted a real dialect validation test: HQL `date_trunc` against an actual PostgreSQL instance, not just H2. The path there required understanding exactly how Quarkus locks in datasource configuration at augmentation time.

Attempt one: `@TestProfile` pointing to `db-kind=postgresql` with no JDBC URL, expecting Dev Services to start a container. The error: `Datasource deactivated automatically because its URL is not set.` Dev Services activation is decided at augmentation time. If any JDBC URL was configured in `application.properties` when the module was built, Dev Services is off for that session — test resource overrides arrive too late.

Attempt two: `QuarkusTestResourceLifecycleManager` that starts a PostgreSQL Testcontainer and injects the concrete JDBC URL. The error: `Driver does not support the provided URL: jdbc:postgresql://localhost:45387/test`. Quarkus had baked H2's driver class into Agroal's connection factory at augmentation. The URL was right; the driver class wasn't.

Attempt three: `reuseForks=false` for a clean JVM. Same error. The augmented artifact lives in `target/quarkus-app/` on disk — a fresh JVM reads the same cache.

What works:

- Set `quarkus.datasource.db-kind=postgresql` as a Surefire **system property** — visible to the augmentation cache check before the JVM starts
- Run the PostgreSQL execution **first** in the POM, before the H2 execution — so the fresh augmentation uses PostgreSQL; H2 detects the mismatch and re-augments second
- Use `QuarkusTestResourceLifecycleManager` to start the container and inject the JDBC URL into the runtime phase

And then the test ran and Flyway failed immediately. `V13__confidence_score.sql` uses bare `DOUBLE` — H2 accepts it, PostgreSQL requires `DOUBLE PRECISION`. We bypassed Flyway in the PostgreSQL test and used Hibernate schema generation instead. But the discovery stands: the migrations aren't PostgreSQL-compatible. They've only ever been tested on H2. That's a production bug, now at least a visible one.

73 tests passing — 68 against H2, 5 against real PostgreSQL.
