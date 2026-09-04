# Session Workbench — blocks-ui Component Design

**Issue:** casehubio/blocks-ui#97 (component), casehubio/devtown#123 (consumer)
**Date:** 2026-07-27
**Approach:** Flat composables with platform-standard data patterns

## Overview

A reusable session management workbench for CaseHub applications that consume claudony worker sessions. Three composable Lit Web Components following the established DataSourceMixin and pages-event patterns. The workbench composes them into a split-pane layout via `blocks-split-workbench`; each sub-component also works standalone.

## Package Structure

| Package | Element | Purpose |
|---------|---------|---------|
| `components/session-list/` | `<blocks-session-list>` | Session table with status badges, inline spawn form, per-row lifecycle actions |
| `components/session-detail/` | `<blocks-session-detail>` | Tabbed detail pane: Terminal, Git, Health, Events |
| `components/session-workbench/` | `<blocks-session-workbench>` | Composes list + detail in `split-workbench` — composition shell only |

## Data Fetching Architecture

### Reads — DataSourceMixin / DataSourceAdapter

The session list follows the work-item-inbox pattern — direct `fetch()` with raw array state and `fromRows()` conversion for pages-table:

```
session-list fetches GET /api/sessions
    ↓
sessions: SessionResponse[] (raw array — retained for selection, restart capture, optimistic updates)
    ↓
fromRows(sessions, SESSION_COL_DEFS) → TypedDataSet
    ↓
pages-table renders
```

DataSourceMixin is not used because it produces TypedDataSet through the extraction pipeline, losing the raw SessionResponse objects needed for selection payloads, restart detail capture, and optimistic array manipulation. The `fromRows` approach (from `@casehubio/pages-data`) is the proven pattern for components that need both raw object access and pages-table rendering.

Detail tabs that produce tabular data (Health) use a DataSourceAdapter instance internally. Detail tabs that produce non-tabular data (Git status — single object, Terminal — plain text) use direct `fetch()` with component-managed loading/error state.

### SSE — SSEManager

The Events tab uses SSEManager from `@casehubio/pages-data` — the same abstraction work-item-inbox uses for real-time work item events. SSEManager provides explicit `subscribe(url, handler)` / `unsubscribe(url, handler)` for dynamic URL changes, connection pooling (shares EventSource instances across subscribers for the same URL), and automatic reconnection with exponential backoff.

EventStreamController (blocks-ui-core) is not suitable here: its static lifecycle model (URL set once in constructor, connect on `hostConnected`, disconnect on `hostDisconnected`) doesn't handle dynamic `sessionId` changes. SSEManager's explicit subscribe/unsubscribe naturally handles tab activation and session switching: unsubscribe old URL, subscribe new URL.

### Mutations

No platform abstraction exists for mutations. Create, delete, and rename use direct `fetch()` calls. On successful mutation, the component emits `session:changed` via `emitPagesEvent`, which triggers a list refresh.

### Event Communication

All inter-component events use `emitPagesEvent` / `onPagesEvent` from blocks-ui-core with colon-delimited topic hierarchies (ARC42STORIES §8):

- `session:selected` — emitted by session-list on row activation (payload: `{ id }`)
- `session:deselected` — emitted by session-list, split-workbench back button, or programmatically
- `session:changed` — emitted by session-list after mutation (payload: `{ action: 'created' | 'deleted' | 'restarted' }`)
- `session:refresh` — emitted to trigger session-list re-fetch

split-workbench subscribes to `session:selected` and `session:deselected` internally to handle show/hide transitions — no workbench-level forwarding needed.

## Types

Mirror claudony server models:

```typescript
// Verified against SessionResponse.java, SessionStatus.java, GitStatusResponse.java,
// PortStatus.java, CreateSessionRequest.java in claudony (commit 3061ba9 verification)

interface SessionResponse {
  id: string;
  name: string;
  workingDir: string;
  command: string;
  status: SessionStatus;
  createdAt: string;
  lastActive: string;
  wsUrl: string;
  browserUrl: string;
  instanceUrl?: string;
  instanceName?: string;
  stale?: boolean;
  expiryPolicy?: string;
  caseId?: string;
  roleName?: string;
}

type SessionStatus = 'ACTIVE' | 'WAITING' | 'IDLE';

interface CreateSessionRequest {
  name: string;
  workingDir?: string;
  command?: string;
  expiryPolicy?: string;
}

interface GitStatusResponse {
  gitRepo: boolean;
  githubRepo?: string;
  branch?: string;
  pr?: PrInfo | null;
  error?: string;
}

interface PrInfo {
  number: number;
  title: string;
  url: string;
  state: string;
  checksTotal: number;
  checksPassed: number;
  checksFailed: number;
  checksPending: number;
}

interface PortStatus {
  port: number;
  up: boolean;
  responseMs: number;
}
```

**Status semantics (from `SessionStatus.java`):**
- `ACTIVE` — Claude is actively responding
- `WAITING` — Claude has shown a prompt, awaiting user input
- `IDLE` — shell prompt visible, no Claude process running

There is no STOPPED state. Claudony sessions are either alive (ACTIVE/WAITING/IDLE) or deleted — `DELETE /api/sessions/{id}` kills the tmux session and removes it from the registry.

**Fields `wsUrl` and `browserUrl`:** Always present for local sessions (constructed from port + session ID). Nullable for remote/stale sessions. Included for type completeness even though terminal interaction is deferred to a follow-up issue.

**Error handling:** `fetch()` calls check `response.ok`. Components catch errors and render inline error state with retry option.

**No auth built in.** Browser cookie/session handles same-origin auth. Cross-origin is CORS configuration — not the component's concern.

## blocks-session-list

**Element:** `<blocks-session-list>`

**Extends:** `LitElement` (with `KeyboardShortcutMixin`, `LiveRegionMixin`)

**Data model:** Follows the work-item-inbox pattern — maintains a raw `sessions: SessionResponse[]` array as component state, builds TypedDataSet via `fromRows(sessions, SESSION_COL_DEFS)` for pages-table rendering. This gives raw object access for selection payloads, restart capture, and optimistic updates — capabilities that DataSourceMixin's TypedDataSet output does not provide.

**Properties:**
- `endpoint: string` — base URL for session API
- `selection-topic: string` — defaults to `"session"`, configures event topic namespace

**Layout (top to bottom):**
1. **Header row** — "Sessions" title + refresh button + "+ New" toggle button
2. **Inline spawn form** — collapsible: name field, working dir field, submit button. Hidden by default, toggled by "+ New".
3. **Session table** — rendered via `pages-table` with `columnConfig` and `columnRenderers`. Columns: name, status badge (green=ACTIVE, amber=WAITING, grey=IDLE), working dir (truncated), created time (relative). Single-click selects.
4. **Row actions** — per-row icon buttons via column renderer: restart (cycle icon), delete (trash icon). Delete uses inline text-swap confirmation (not dialog).

**Data fetching:** Direct `fetch()` to `GET ${endpoint}` on `connectedCallback`. Response parsed into `sessions: SessionResponse[]`. TypedDataSet built via `fromRows(sessions, SESSION_COL_DEFS)` and passed to pages-table. Refresh triggered by:
- `session:refresh` pages-event (listened via `onPagesEvent`)
- Manual refresh button click
- After successful mutation → `emitPagesEvent(document, 'session:refresh', {})`

**Mutations:** Direct `fetch()` calls:
- Create: `POST ${endpoint}` with `CreateSessionRequest` body
- Delete: `DELETE ${endpoint}/${id}`
- Rename: `PATCH ${endpoint}/${id}/rename?name=${newName}`

**Optimistic updates:** On delete, session removed from the `sessions` array immediately. On create, new session prepended. TypedDataSet rebuilt via `fromRows`. Reconciled on next fetch.

**Events emitted (all via `emitPagesEvent`):**
- `session:selected` — `{ id }` on row click (ID only, matching work-item-inbox pattern)
- `session:deselected` — `{}` when selection cleared
- `session:changed` — `{ action }` after mutation
- `session:refresh` — after mutation (triggers own re-fetch and any other listeners)

**Standalone use:** Component fetches from `endpoint` directly on `connectedCallback`.

### Restart Failure Handling

No server-side restart endpoint exists in claudony. Restart is delete (`DELETE`) then create (`POST`) with the same name/workingDir/command. There is no "stop" distinct from delete — claudony sessions are alive or deleted.

**Failure mode:** If delete succeeds but create fails (network error, name conflict, server error), the session is gone with no replacement.

**Recovery:** The component:
1. Captures session details (name, workingDir, command) before issuing delete
2. If create fails: shows an inline error banner with "Retry Create" button
3. The retry button re-attempts `POST` with the captured details
4. The error banner persists until create succeeds or the user dismisses it

## blocks-session-detail

**Element:** `<blocks-session-detail>`

**Properties:**
- `endpoint: string` — base URL
- `sessionId: string` — which session to show

**Internal wiring:** On `connectedCallback`, subscribes via `onPagesEvent` to:
- `session:selected` — extracts `id` from payload, sets `sessionId`, fetches full session via `GET ${endpoint}/${id}`
- `session:deselected` — clears `sessionId` to `undefined`, triggering teardown of all active tab resources (timers, SSE)

In desktop mode, split-workbench renders both panels unconditionally — the detail panel stays in the DOM even when no session is selected. Without deselection handling, component-local timers and SSE connections would leak for hidden sessions.

**Tabs:**

### Terminal
- **Output area** — monospace pre-formatted text from `GET ${endpoint}/${sessionId}/output?lines=200`. Direct `fetch()` (returns `text/plain` — TypedDataSet does not apply). Auto-refreshes on 2s component-local `setInterval` while tab is active. Scrolls to bottom on new content.

### Git
- Direct `fetch()` to `GET ${endpoint}/${sessionId}/git-status` on tab activation.
- Shows: branch name, repo name, PR info (number, title, state, link), check rollup (passed/failed/pending with colour).
- Fallbacks: "Not a git repository" or "No open PR for this branch".

### Health
- DataSourceAdapter instance with endpoint `${endpoint}/${sessionId}/service-health`.
- Rendered via `pages-table` — columns: port number, status (green/red dot), response time ms.
- Refreshes on tab activation + 10s component-local `setInterval`.
- Only shows ports that are up.

### Events
- SSEManager instance (`@casehubio/pages-data`) with explicit subscribe/unsubscribe.
- On tab activation: `sseManager.subscribe(\`${endpoint}/${sessionId}/case-events\`, handler)`.
- On tab deactivation or sessionId change: `sseManager.unsubscribe(oldUrl, handler)`.
- Chronological feed: timestamp, event type, compact JSON payload.
- SSEManager handles reconnection with exponential backoff automatically.
- If no caseId on the session: "No case linked to this session".

**Tab lifecycle:** Only the active tab fetches/subscribes. Component-local timers torn down on tab switch or `sessionId` change. SSE connections managed via SSEManager subscribe/unsubscribe. Setting `sessionId` to `undefined` (via `session:deselected`) triggers teardown for all active tab resources.

**Empty state:** When no `sessionId`, shows "Select a session" placeholder.

## blocks-session-workbench

**Element:** `<blocks-session-workbench>`

**Properties:**
- `endpoint: string` — base URL (only required prop)

**Composition shell.** Follows the work-item-workbench pattern — renders `<blocks-split-workbench>` with children in named slots. The workbench does not intercept or forward events. Children communicate through pages-event; split-workbench handles show/hide transitions internally.

```html
<blocks-split-workbench selection-topic="session">
  <blocks-session-list slot="list"
    .endpoint=${this.endpoint}
  ></blocks-session-list>
  <blocks-session-detail slot="detail"
    .endpoint=${this.endpoint}
  ></blocks-session-detail>
</blocks-split-workbench>
```

**`configure()` method:** Accepts `{ endpoint: string }` — called before DOM attachment by the pages runtime. Follows the same convention as work-item-workbench (inline method definition, not a formal interface type).

```typescript
configure(props: { endpoint?: string }): void {
  if (props.endpoint !== undefined) this.endpoint = props.endpoint;
  this.requestUpdate();
}
```

**Layout:** 50/50 default split (split-workbench default `_dividerRatio = 0.5`). Uses split-workbench's draggable divider and responsive collapse. Ratio persisted to localStorage automatically via split-workbench.

## Accessibility

- **Workbench:** `KeyboardShortcutMixin` for global shortcuts (following work-item-workbench pattern): `?` toggles shortcut overlay, `Escape` closes overlay / deselects.
- **Session list (pages-table):** Built-in keyboard navigation — arrow keys to move between rows, Enter to select/activate. Handled by pages-table internally.
- **Detail tabs:** Standard ARIA tabpanel pattern — `role="tablist"`, `role="tab"` with `aria-selected`, `role="tabpanel"` with `aria-labelledby`. Arrow keys navigate between tabs.
- **Focus management:** split-workbench handles focus transfer between list and detail panels on selection/deselection via `_focusSlot()`. Back button in responsive mode returns focus to list.
- **Live region:** split-workbench uses `LiveRegionMixin` to announce "Showing detail" / "Showing list" transitions.
- **Status badges:** Use `aria-label` to convey status (e.g., `aria-label="Status: Active"`, `aria-label="Status: Waiting"`).

## Devtown Integration

Devtown's governance frontend adds a "Workers" tab with minimal code:

1. **Dependency** — add `@casehubio/blocks-ui-session-workbench` to `app/src/main/webui/package.json`
2. **Register** — `registerPanel('session-workbench', 'blocks-session-workbench')` in `index.ts`
3. **Tab** — new "Workers" entry in `tabs()`, using `hostPanel` to mount with `endpoint: "/api/sessions"`
4. **Proxy** — devtown proxies `/api/sessions/*` to claudony (unless co-deployed on same origin)

## Related Issues

- casehubio/engine#786 — bulk worker/flow instance state query API (future: enables fleet-level worker views beyond claudony sessions)
- casehubio/claudony#TBD — server-side restart endpoint (`POST /api/sessions/{id}/restart`) for atomic restart
- casehubio/blocks-ui#TBD — terminal interaction (sendInput, resize, openTerminal) for session-detail
- casehubio/blocks-ui#TBD — worker lineage tab for session-detail (depends on verifying `WorkerSummary` type from engine API)

## Testing Strategy

- **session-list:** Vitest + jsdom — renders sessions via pages-table, spawn form creates, delete removes, selection emits `session:selected` with `{ id }` payload via emitPagesEvent. Optimistic update and fromRows rebuild tested.
- **session-detail:** Vitest + jsdom — tab switching lifecycle (timers start/stop), terminal output rendering, SSEManager subscribe/unsubscribe for Events tab, deselection clears sessionId and tears down resources.
- **session-workbench:** Integration test — selection flows from list to detail via pages-event, mutation triggers refresh via `session:refresh`.
- **Showcase app:** Add pages to the Vite showcase app:
  - `session-list-page.ts` — mock session data, spawn form interaction, status badge variants
  - `session-detail-page.ts` — tab switching, terminal output, mock git/health/event data
  - `session-workbench-page.ts` — full composition, selection flow, responsive collapse
