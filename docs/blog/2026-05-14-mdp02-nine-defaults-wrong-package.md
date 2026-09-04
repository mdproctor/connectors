---
layout: post
title: "Nine defaults, one wrong package"
date: 2026-05-14
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [java, quarkus, cdi, arc]
---

When a platform's no-op SPI defaults use bare `@ApplicationScoped`, consumer
repos pay the price. CDI sees two qualifying beans for the same injection point
— the engine's fallback and the consumer's real implementation — and fails at
boot with an ambiguity error. The workaround (listing each no-op in
`quarkus.arc.exclude-types` per consumer) rots silently as new no-ops get added.

The fix: annotate every no-op default `@DefaultBean @ApplicationScoped`. CDI
then yields the fallback automatically to any non-default qualifying bean — no
consumer configuration required.

There were nine of them, not seven. The original task listed four blocking beans
and three reactive variants. I added `EmptyWorkerContextProvider` when I noticed
it sitting bare during the design review. Claude found the ninth,
`EmptyReactiveWorkerContextProvider`, while reading the existing unit test — it
appeared in the test's own import list, a reactive mirror of the context provider
that had been `@Alternative` and missed the original scope.

## The package that doesn't exist

The plan Claude drafted specified `import jakarta.enterprise.inject.DefaultBean`.
The assumption is natural — `@Alternative` lives in `jakarta.enterprise.inject`,
so you'd expect its counterpart in the same package. The assumption is wrong.

`@DefaultBean` isn't a CDI 4.x annotation. It's a Quarkus Arc extension, defined
in `io.quarkus.arc`:

```java
// This class does not exist
import jakarta.enterprise.inject.DefaultBean;

// Correct
import io.quarkus.arc.DefaultBean;
```

The compile error was immediate. Finding the right package required checking the
jars directly — `jar -tf` on the Arc artifacts confirmed
`io/quarkus/arc/DefaultBean.class`. No additional dependency was needed;
`io.quarkus.arc:arc` is already on compile scope transitively in every Quarkus
project. We got all nine beans corrected and 506 tests passing.

The guidance for adding new SPI no-ops now includes the `@DefaultBean` requirement
and the correct import — the path that cost us a compile cycle won't cost the next
person the same.

## Before committing to the pattern

Before any implementation I pressed on whether `@DefaultBean` was the right
approach at all. The reactive variants were already using `@Alternative`, which
avoids the CDI collision — just less elegantly. It requires explicit activation
per consumer and breaks silently when new no-ops are added. `@DefaultBean` says
what it means: this is the fallback, replace me.

One loose end: `JsonPatchContextDiffStrategy` is still `@Alternative`, leaving an
inconsistency in the diff package where the no-op is now `@DefaultBean` and the
real opt-in implementation still uses the old pattern. That's a conversation for
the next session.
