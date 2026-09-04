---
layout: post
title: "Documentation Debt Has a Half-Life"
date: 2026-06-28
type: phase-update
entry_type: note
subtype: diary
projects: [casehub]
tags: [documentation, neural-text, deep-dive]
---

## Documentation Debt Has a Half-Life

The neural-text deep-dive needed four new items: `MatryoshkaEmbeddingModel`, `DenseQuantization`, search-time oversampling, and the CDI wiring that ties them together. All shipped in neural-text#31, all verified against source code — straightforward sync work.

What wasn't straightforward: the document we were updating had rotted. The #17 refactor renamed `CorpusStore` to `EmbeddingIngestor` three weeks ago to free the name for the new corpus storage module. The deep-dive still said `CorpusStore` in five places — the rag-api module row, the rag-testing row, the Key Abstractions header and body, and the C7 current-state row. `QdrantCaseRetriever`, which never existed under that name, was listed as a shipped artifact. We were about to write correct new content into a document with wrong old content.

The fix was mechanical — find every stale reference, replace it, update the `EmbeddingIngestor` description to match what the interface actually does (pre-chunked text, not documents). But the finding matters more than the fix. The rename was deliberate — the commit message even said "Free the CorpusStore name" — and the deep-dive was updated in the same session. Three weeks later, four of the five locations had already been missed. Documentation debt doesn't wait for neglect; it appears the moment a refactor touches more files than the author remembers to update.

The other thing worth noting: the review caught that "search-time oversampling" doesn't belong as a Key Abstraction. Every existing entry in that section is a class, SPI, or component subsystem. Oversampling is a parameter on `HybridCaseRetriever` — putting it alongside `InferenceModel` and `SparseEmbedder` would be a category error. It belongs in the CaseRetriever description where it's a behavior, not an abstraction. Small structural discipline, but the kind that keeps a living document navigable as it grows.