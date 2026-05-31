# 0002 — IMAP IDLE replaces polling in EmailInboundConnector

Date: 2026-05-31
Status: Accepted

## Context and Problem Statement

`EmailInboundConnector` was implemented with a `ScheduledExecutorService` poll model
(one daemon thread per account, fresh IMAP connection per poll cycle). Issue #9 required
near-real-time inbound message delivery — latency of up to `pollIntervalSeconds` was
unacceptable for an agent communication platform.

## Decision Drivers

* Sub-second delivery latency for inbound emails
* Foundation repo — code path clarity and long-term maintainability matter more than
  backward compatibility with unusual IMAP servers
* Virtual threads (Java 21) eliminate the OS thread cost of persistent blocking connections

## Considered Options

* **Option A** — IMAP IDLE only (RFC 2177); no polling fallback; fail loudly if IDLE unsupported
* **Option B** — IMAP IDLE primary, automatic polling fallback when server rejects IDLE
* **Option C** — Config-driven: `idle: true/false` per account; both paths first-class

## Decision Outcome

Chosen option: **Option A**, because IDLE support is universal in target deployments
(RFC 2177 has been supported by Gmail, Outlook, Fastmail, Dovecot, Exchange for 20+ years),
two code paths compound maintenance cost indefinitely, and loud failure on a misconfigured
server is better than silent degradation to polling.

### Positive Consequences

* Single IDLE loop per account; one code path to maintain and test
* Sub-second delivery latency (server push on message arrival)
* Virtual thread per account: zero OS thread cost during blocking IDLE waits
* Reconnect with exponential backoff provides resilience without fallback complexity

### Negative Consequences / Tradeoffs

* Hard dependency on IMAP IDLE support; any server that doesn't support IDLE requires
  a different connector implementation rather than a config flag
* Persistent IMAP connection per account rather than stateless-per-poll — connection
  lifecycle (race guard, volatile stopping flag, CopyOnWriteArrayList of open stores)
  is more complex than the polling model

## Pros and Cons of the Options

### Option A — IDLE only

* ✅ Single code path; no state machine distinguishing IDLE vs polling accounts
* ✅ Sub-second latency
* ✅ Loud failure on unsupported server — operators see a clear SEVERE log, not silent degradation
* ❌ No graceful degradation to polling for non-IDLE servers (none exist in target deployments)

### Option B — IDLE + polling fallback

* ✅ Handles unusual servers gracefully
* ❌ Requires failure categorisation (transient network vs permanent IDLE rejection) using
  fragile exception-message inspection
* ❌ Two execution models (virtual thread IDLE loop + ScheduledExecutorService) with separate
  shutdown coordination and diverging edge-case handling

### Option C — Config-driven

* ✅ Maximum flexibility
* ❌ Adds per-account configuration surface for a capability split that doesn't exist in practice
* ❌ Two code paths plus migration friction when all accounts eventually move to IDLE

## Links

* casehubio/connectors#9 — IMAP IDLE feature issue
* `EmailInboundConnector.java` — implementation
* ADR 0001 — inbound connector type separation (pull vs webhook)
