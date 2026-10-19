# tc-lsp: LSP Timeout Convergence Notes

## Objective
Converge LSP invocation stop model to single context.signal without secondary query-level timer, align lifecycle defaults to 180s, preserve boot waiter isolation, and distinguish cancellation from timeouts.

## Changes
- `daemon/src/coding/lsp-client.ts`:
  - `initialize` default timeout changed from 60,000ms to 180,000ms.
  - Removed `timeoutMillis` parameter from public `definition`, `workspaceSymbols`, and `javaDecompile`.
  - Refactored `sendRequest` to take `{ signal, timeoutMillis, requireAlive }`. Lifecycle timer is enabled only when `timeoutMillis !== undefined && timeoutMillis > 0` (preserving ZERO infinite timeout semantics).
  - On signal abort, triggers `cts.cancel()` to transmit `$/cancelRequest` and rejects with specialized timeout error when `signal.reason === 'TIMED_OUT'`.
- `daemon/src/coding/lsp-pool.ts`:
  - `initializeTimeout` default changed from 60,000ms to 180,000ms.
  - `WaiterAbortError`: tracks caller-driven cancellation or TIMED_OUT aborts.
  - Decrements `boot.waiters` only on caller `WaiterAbortError`, keeping other waiters safe when one waiter exits. Aborts boot only when the last remaining waiter exits.
  - Server initialization timeout/failures are preserved and surfaced as server failures without misclassifying as caller cancellation.
- `daemon/src/coding/lsp-service.ts`:
  - Removed `timeoutMillis` from `gotoDefinition`, `workspaceSymbols`, `javaDecompile`, forwarding `signal?: AbortSignal` across pool lease and client query calls.
- `daemon/src/coding/lsp.ts`:
  - Handled `signal.reason === 'TIMED_OUT'` and timeout errors to return `errorResult(callId, 'Operation timed out')`, strictly distinguishing from user cancellation (`Operation cancelled`).
- Tests:
  - `daemon/test/lsp/lsp-protocol.test.ts`: Updated queries to remove redundant timeout parameters; converted timeout tests to deterministic `AbortController` + event await; verified `$/cancelRequest` and process survival; added ZERO infinite timeout check.
  - `daemon/test/lsp/lsp-capabilities.test.ts`: Added pre-aborted and in-flight TIMED_OUT regression tests across capabilities; updated service call signature.
  - `daemon/test/lsp/lsp-pool.test.ts`: Added default 180s timeout verification, shared waiter isolation with TIMED_OUT, all-waiter exit cleanup, and boot initialization failure preservation.

## Validation
- `npm run check` (tsc --noEmit): PASS
- `npm run build`: PASS
- `npx vitest run test/lsp`: PASS (6 test files, 83 tests)
- Scoped Coverage:
  - `lsp-client.ts`: 95.73% lines, 90.98% branches
  - `lsp-codec.ts`: 100% lines, 95.23% branches
  - `lsp-discovery.ts`: 98.11% lines, 97.45% branches
  - `lsp-pool.ts`: 96.90% lines, 90.21% branches
  - `lsp.ts`: 96.55% lines, 98.30% branches
- Sensitive data check: PASS (0 output)
