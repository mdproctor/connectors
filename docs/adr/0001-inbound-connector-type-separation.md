# 0001 — Separate types for pull-based and webhook-based inbound connectors

Date: 2026-05-29
Status: Accepted

## Context and Problem Statement

casehub-connectors adds inbound message transport. Inbound connectors fall into two
fundamentally different transport models: pull-based (e.g. IMAP polling, which actively
fetches messages on a schedule) and webhook-based (Slack, Teams, WhatsApp, Twilio SMS,
which receive HTTP POST requests passively). Both need to deliver messages to the same
CDI event bus (`InboundConnectorService.receive()`), but their lifecycle semantics differ
completely.

## Decision Drivers

* Pull connectors require `start(InboundMessageSink)` / `stop()` lifecycle methods —
  called at Quarkus startup/shutdown to begin/end polling
* Webhook connectors have no polling lifecycle — their lifecycle IS the JAX-RS endpoint
* `InboundConnectorService.onStart()` must only call `start(sink)` on connectors that
  actually have a meaningful lifecycle; calling it on webhook connectors is semantically
  incorrect and misleading to future readers
* CDI `@All List<T>` gives type-safe separate discovery lists — there is no cost to
  using two types

## Considered Options

* **Option A — Unified `InboundConnector` SPI** — single interface with `start(sink)` and
  `stop()` for all transports; webhook connectors implement with `final` no-op methods
* **Option B — Separate types** (chosen) — `InboundConnector` interface for pull-based;
  `WebhookInboundConnector` standalone abstract class for webhook-based; both deliver via
  `InboundConnectorService.receive()`
* **Option C — No shared base type** — each connector is fully independent; the CDI event
  bus is a standalone CDI bean injected directly by webhook connectors

## Decision Outcome

Chosen option: **Option B — Separate types**, because the lifecycle semantics of pull and
webhook connectors are genuinely different. A unified interface with no-op `start()`/`stop()`
makes the contract misleading: readers see lifecycle methods on webhook connectors and must
inspect the implementation to learn they are no-ops. Java's type system can express the
distinction cheaply — using two types makes the contract self-documenting.

### Positive Consequences

* `InboundConnectorService.onStart()` only calls `start(sink)` on connectors that have
  a real lifecycle — no misleading no-op calls
* CDI discovers pull connectors via `@All List<InboundConnector>` and webhook connectors
  via `@All List<WebhookInboundConnector>` — type-safe, no `instanceof` needed
* Adding a pull connector in future (connectors#7 — IMAP) requires no changes to the
  webhook infrastructure, and vice versa

### Negative Consequences / Tradeoffs

* Two types instead of one — a new contributor must understand both
* No single method to enumerate all inbound connectors regardless of type; callers must
  query `InboundConnectorService.pullIds()` and `WebhookRouter.webhookIds()` separately

## Pros and Cons of the Options

### Option A — Unified SPI

* ✅ Single type — simpler to discover and explain
* ✅ All connectors discoverable from `@All List<InboundConnector>`
* ❌ Webhook connectors must implement `start(sink)` / `stop()` as explicit no-ops
* ❌ `InboundConnectorService.onStart()` calls no-op methods on all webhook connectors
  at startup — semantically wrong and confusing to future readers
* ❌ `final` no-ops on `WebhookInboundConnector` are a code smell that signals bad design

### Option B — Separate types (chosen)

* ✅ Contract is self-documenting — `InboundConnector` means lifecycle; `WebhookInboundConnector`
  means no lifecycle
* ✅ No misleading no-op calls at startup
* ✅ Extensible independently — pull and webhook stacks evolve without coupling
* ❌ Two types to understand instead of one
* ❌ No unified enumeration across both types

### Option C — No shared base type

* ✅ Maximally decoupled — each connector is entirely independent
* ❌ No compile-time enforcement that webhook connectors implement `id()` and `handle()`
* ❌ Webhook connectors must inject `InboundConnectorService` directly, creating an
  upward dependency from implementation to service layer

## Links

* casehubio/connectors#4 — implementing issue
* `docs/specs/2026-05-29-inbound-connector-spi-design.md` — full design spec
* Protocol PP-20260529-7b94ab — `inbound-connector-type-separation.md`
