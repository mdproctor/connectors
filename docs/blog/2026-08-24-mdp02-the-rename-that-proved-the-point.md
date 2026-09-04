---
layout: post
title: "The Rename That Proved the Point"
date: 2026-08-24
entry_type: note
subtype: diary
projects: [casehubio/engine]
tags: [refactoring, type-safety, llm-productivity, intellij, empirical]
series: issue-422-ts-programming-model
---

# The Rename That Proved the Point

Three fields needed renaming across two repositories. `Worker.capabilityNames` to `capabilities`. `Capability.inputSchema` to `inputProjection`. `Capability.outputSchema` to `outputProjection`. Mechanical work. The kind of thing a competent engineer does in thirty seconds with an IDE refactoring.

Claude tried it without the IDE first. The results are worth documenting because they're not anecdotal. Every tool call, every compile cycle, every false positive is in the session transcript. This is measured data from a real cross-repo rename on a codebase with 99 affected Java files across 30+ modules.

## The text-based attempt

Claude attempted the rename with per-file text replacement — search for `.capabilityName(`, replace with `.capability(`. Search for `.inputSchema(`, replace with `.inputProjection(`. Repeat across each of the 99 affected files.

The first false positive appeared on file four.

`CaseDefinitionRecorder` calls `wd.capabilityName()` on `WorkerDescriptor` and `bd.capabilityName()` on `BindingDescriptor`. These are different types with the same method name. The text replacement renamed them all. The compiler caught it — `WorkerDescriptor` has no `capability()` method. Two reversals, three extra tool calls.

`CandidateMatchingStrategyContractTest` calls `ctx.capabilityName()` on `CandidateMatchingContext`. Same pattern — different type, same text, wrong rename. Another reversal.

Maven compiles sequentially. Each module's test sources only compile after the previous module succeeds. This creates a whack-a-mole pattern: fix module A, recompile, discover module B is broken, fix B, recompile, discover module C. Six compile cycles before giving up, each exposing the next layer of failures.

At compile cycle six, the build reported 200 errors across 25 runtime test files. Modules further downstream — planning (26 test files), resilience (5), flow, a2a, mcp, react — hadn't been reached by the compiler yet.

Claude reported the situation as "tedious" and asked whether to continue or find a better approach. The text-based rename was abandoned incomplete.

## The measured cost

The 69 tool calls before abandonment break down by phase:

| Phase | Tool calls | What happened |
|-------|-----------|---------------|
| Production code fixes (`.capabilityNames()` accessor) | 17 | One `ide_replace_text_in_file` per source file |
| Production code fixes (`.inputSchema(`/`.outputSchema(`) | 14 | Across 6 source files |
| Test file fixes (6 compile cycles) | 20 | Each cycle exposed the next module |
| Compile attempts | 9 | Maven sequential — each pass reveals one module |
| Error investigation | 5 | Read logs, count errors, find affected files |
| False positive reversals | 4 | 2 on WorkerDescriptor, 1 BindingDescriptor, 1 CandidateMatchingContext |
| **Total at abandonment** | **69** | **33 of 99 files fixed** |

### Projection to completion

Two independent methods to estimate remaining work:

**Source 1 — IntelliJ ground truth.** The successful IDE refactor (attempt 2) changed 49 engine Java files. Text approach fixed 33. Remaining: 16.

**Source 2 — text search results.** Searching for `.inputSchema(` returned 195+ matches across the engine. Grouping unique files by module: 29 runtime test files, 26 planning, 5 resilience, plus flow, a2a, mcp. Remaining: ~61.

The discrepancy between 16 and 61 is itself evidence. Source 2 over-counts because text search cannot distinguish `Capability.inputSchema()` (should rename) from `JsonNode.get("inputSchema")` (should not) from `"inputSchema"` in a YAML test string (should not). The text-based approach would attempt replacements on all 61 files, producing false positives on ~45 of them. The IDE refactor correctly touched only 16 because it operates on the AST, not text.

Using Source 2 (what the text approach would actually attempt):

| Metric | At abandonment | Projected to completion |
|--------|---------------|------------------------|
| Tool calls | 69 | ~251 |
| Files fixed | 33 of 99 | 99 |
| Compile cycles | 6 | ~11 |
| False positives caught | 4 | ~16 (at observed 20% rate) |
| Wall time | ~25 min | ~45 min |
| Estimated tokens | ~45k | ~120k |

**Token cost method:** Each `ide_replace_text_in_file` call ≈ 350 tokens (request + response). Each compile cycle ≈ 2,500 tokens (command + output + error investigation). Each false positive reversal ≈ 1,500 tokens (investigation + reversal + recompile). Reasoning between calls ≈ 300 tokens average.

The false positive rate (4 out of 20 `.capabilityName(` replacements) is from a small sample — the true rate depends on how many types in the codebase share the renamed method name. In a platform with routing strategies, contexts, and plan steps all carrying a `capabilityName()` accessor, 20% is conservative.

## The type-safe attempt

After reverting all partial changes, the workspace was reopened with the worker repo included as a source module — not a jar dependency. IntelliJ resolved `io.casehub.worker.api.Worker` from the workspace source, giving it visibility into all callers across both repositories.

Three `ide_refactor_rename` calls. One per field.

| Rename | Files changed | False positives |
|--------|--------------|-----------------|
| `capabilityNames` to `capabilities` | 25 | 0 |
| `inputSchema` to `inputProjection` | 41 | 0 |
| `outputSchema` to `outputProjection` | 33 | 0 |

Total: 8 tool calls. One compile pass. Zero errors. Roughly two minutes.

## The comparison

| Metric | Text-based (projected) | IDE refactor (actual) | Ratio |
|--------|----------------------|----------------------|-------|
| Tool calls | ~251 | 8 | 31x |
| Estimated tokens | ~120k | ~5k | 24x |
| Estimated cost (USD) | ~$6.12 | ~$0.26 | 24x |
| Wall time | ~45 min | ~2 min | 23x |
| Compile cycles | ~11 | 1 | 11x |
| False positives | ~16 + unknown silent | 0 | - |

### Token cost breakdown

Claude Opus 4 pricing at time of session: $15/M input tokens, $75/M output tokens.

| Cost component | Text-based | IDE refactor |
|---------------|-----------|-------------|
| File replacement calls | 150 × 350 = 52,500 | — |
| Rename calls | — | 3 × 700 = 2,100 |
| Compile cycles | 11 × 2,500 = 27,500 | 1 × 2,500 = 2,500 |
| False positive reversals | 16 × 1,500 = 24,000 | — |
| Error investigation | 15 × 600 = 9,000 | — |
| Sync + misc calls | — | 1 × 200 = 200 |
| Post-processor cleanup | — | 1 × 350 = 350 |
| Reasoning between calls | 251 × 300 = 75,300 | 8 × 300 = 2,400 |
| | | |
| **Total tokens** | **~120,000** | **~5,000** |
| Output tokens (60%) | 72,000 × $75/M = $5.40 | 3,000 × $75/M = $0.23 |
| Input tokens (40%) | 48,000 × $15/M = $0.72 | 2,000 × $15/M = $0.03 |
| **Total cost** | **$6.12** | **$0.26** |
| **Ratio** | | **24x** |

This is a single 3-field rename. A codebase that accumulates a dozen such renames over its lifetime pays the ratio repeatedly.

The ratios are large but the qualitative gap is larger. The text-based approach is semantically unsound — it cannot distinguish `Worker.Builder.capabilityName()` from `CandidateMatchingContext.capabilityName()`. Both are `.capabilityName(` in text. Only one should be renamed. The four caught false positives were caught because the target type lacked a `capability()` method, producing a compile error. Any false positive where the target type *does* have a compatible method signature compiles silently and produces a runtime bug. There is no way to quantify how many of those exist without type analysis — which is precisely what the IDE refactoring provides.

### Methodological caveats

These numbers come from a single session on a single codebase. For academic honesty:

1. The 20% false positive rate is from a small sample (n=20 replacements yielded 4 false positives). The true rate depends on codebase naming conventions — projects with more shared method names across types would see higher rates.
2. Token estimates are ±30%. Exact per-call token counting is not available at the tool-call level; the figures are computed from request/response size estimates.
3. The text-based projection assumes no additional complications (merge conflicts, missed patterns, encoding issues). Real-world execution would likely cost more.
4. The "silent false positive" risk is real but unquantifiable from this data alone. It requires a codebase where multiple types share both the method name and a compatible parameter signature — common in platforms with strategy/context pattern hierarchies, less common in simpler codebases.

## What this tells us about language choice at scale

The text-based approach failed on Java, but Java had a way out. The IDE refactoring operates on the type graph — it knows that `Worker.Builder.capabilityName()` and `CandidateMatchingContext.capabilityName()` are different symbols because the type system makes that distinction structural, not nominal. The 31x cost ratio measures the difference between having that escape hatch and not having it.

Python doesn't have the escape hatch.

In Python, the text-based approach is not a fallback — it is the approach. PyCharm and rope perform best-effort refactoring, but without static type information they cannot guarantee completeness. A method called `capability_name()` on a `WorkerBuilder` and a method called `capability_name()` on a `CandidateMatchingContext` are textually identical and semantically ambiguous. No tool can distinguish them without type annotations that are complete, correct, and enforced — which, in practice, most Python codebases are not.

This doesn't matter for small projects. A 5-file Flask app doesn't have multiple types sharing method names. The rename is unambiguous because the codebase is small enough that name collisions are unlikely. An LLM can rename by text replacement and get it right.

It breaks down at scale. The false positive rate we measured — 20% on `.capabilityName(` — is a function of how many types in the codebase share the target method name. That number grows with codebase size. In a platform with routing strategies, execution contexts, plan steps, and diagnostic types all carrying a `capabilityName()` accessor, the name space is crowded. In a 99-file rename, 4 false positives were caught by the compiler and an unknown number were not. In a 500-file rename the probability of at least one undetected semantic error approaches certainty.

The cost ratio — 31x in tool calls, 24x in tokens, 23x in time — understates the real difference because it compares a projected completion against an actual completion. The text-based approach was abandoned at 33% progress with an accelerating error rate. Projecting linear completion from a non-linear failure curve is generous.

But the quantitative gap is not the argument. The argument is categorical. A typed language with IDE tooling makes cross-repo refactoring a solved operation — three tool calls, zero errors, two minutes. A dynamically typed language makes the same operation an unsound approximation at any scale where types share method names. The LLM has no in-loop type checker. It discovers errors only via compilation, and only for errors that produce compile failures. Silent type-compatible false positives are invisible.

Type-safe refactoring tools don't make LLMs faster. They make operations possible that are otherwise not safely achievable at engineering scale. Small projects don't surface the difference. Large ones can't afford to ignore it.
