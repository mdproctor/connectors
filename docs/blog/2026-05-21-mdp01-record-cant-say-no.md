---
layout: post
title: "A record can't say no"
date: 2026-05-21
type: phase-update
entry_type: note
subtype: log
projects: [casehub-work]
tags: [builder-pattern, java, design-philosophy, jax-rs]
---

`WorkItemCreateRequest` had 24 parameters. A new field meant adding it to the end
and passing null at every call site you could find. Sometimes you missed one. The
record was too honest about its own constructibility.

The problem with records is that the canonical constructor is always public. Java
doesn't let you restrict it. The only way to make positional construction
structurally impossible is to stop being a record.

The replacement is a `final class` with a private constructor and a static builder
factory. `WorkItemCreateRequest.builder().title("review contract").category("legal").build()`.
Everything else defaults to null. `toBuilder()` for copy-with-overrides. The
constructor takes a `Builder`; nothing else can reach it.

## The annotation processor we didn't use

The obvious follow-on question: should we adopt a builder library rather than
writing one? The two realistic candidates for Quarkus and GraalVM native are
Lombok and Immutables — both compile-time, no reflection, no native image headaches.

Immutables was interesting. You define an abstract class with `@Value.Immutable`,
the processor generates the concrete implementation. Clean generated builder,
correct `equals`/`hashCode`/`toString`, mandatory-field enforcement via bit masks.

But twenty-three of twenty-four fields are nullable. Every one needs `@Nullable`
in the abstract class definition. The generated API is clean; the source definition
is noise. And Immutables was going on one class — not a project-wide adoption, just
a single command object.

The manual builder is about eighty lines. When a new field is added, one place
changes: the builder. We wrote it.

## Precision about cost

During the design discussion I said something imprecise, and we worked out the
right version.

I described the Immutables decision as avoiding "implementation cost" — meaning,
adding annotation processing to a single module for one class. That's the wrong
framing. Implementation cost in design decisions is blast radius, migration effort,
files touched. The argument against Immutables wasn't about effort — it was about
unnecessary complexity.

The distinction matters. "This touches 200 files" is a cost argument — ignore it
when choosing the right architecture. "This adds ceremony with no architectural
benefit" is a design argument — it's why you'd prefer the simpler option. Conflating
them is how you accept worse architectures to avoid work, and reject simpler solutions
because you're trying to prove you'll pay any cost.

I tightened the prompt snippet accordingly: cost is always worth paying for the right
design; unnecessary complexity is bad design regardless of cost. "If 'simpler is
better' crosses your mind, ask whether the simplicity serves the architecture or just
avoids work."

## The endpoint that ignored its own payload

One of the test fixes was more interesting than it looked. A test was sending
`{"claimantId":"alice"}` as a JSON body to the claim endpoint and getting back
"claimantId is required."

The obvious move was to check the field name. Claude changed it from `assigneeId`
to `claimantId`. Same error.

The claim endpoint uses `@QueryParam("claimant")` — no request body annotation.
JAX-RS doesn't warn when a body is sent and ignored. The service got null, threw,
and the error message pointed entirely at the wrong thing.

The fix: `.queryParam("claimant", "alice")`, no body. Changing the field name
couldn't work because the body was never read. The error message tells you what
the code expected, not why the expectation wasn't met.
