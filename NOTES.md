# D1fe: Frontend Incremental Snapshot Loading & Advisory Query Implementation

## Commits
- `293fe3959cb80847b99f551d8661aef5a3dffe7e`: Initial D1fe implementation (incremental snapshot loading, cache merging, independent advisory query).
- `7a27ed7e271582bc55899121c2a436e1f440bab7`: Refactored compaction advisory state to client-owned discriminated union (`status: 'checking' | 'failed' | 'ready'`), restored canonical `manualCompactionDisabledReason` without regex/pass-through, and decoupled client UI loading/failure states from server DTOs.
- Implemented frontend incremental snapshot loading with `afterEntryId` cursor and snapshot cache merging (`thread-snapshot-cache.ts`).
- Removed `manualCompaction` from snapshot contracts and core models; introduced independent compaction advisory query via `GET /api/threads/{id}/compaction-availability` with on-demand lifecycle when command palette opens.
- Replaced client-side CAS pre-checks with authoritative server CAS delegation via `expectedVersion`.
- Preserved purity and safety of TanStack Query: no internal `setQueryData` inside query functions; baseline cache read occurs after network resolution to avoid race conditions.

## Verified Commands & Facts
- `npm run typecheck`: Passed (tsc -b, 0 errors).
- `npm run typecheck:test`: Passed (tsc -p tsconfig.test.json, 0 errors).
- `npm run lint`: Passed (eslint ., 0 errors, 0 warnings).
- Unit tests: 14 test suites, 303 tests passing (including `thread-snapshot-cache.test.ts` with 100% line coverage and `useRootThreadControl.advisory.test.tsx` verifying lifecycle).
- Chromium browser test: `npm run test:pane` executed under `/tmp/tmpdir-7022833470427643707/browser.lock`, 18 tests passing (8.5s).

## Cache Write Audit Findings
All 7 investigated callsites adhere to cache safety rules:
1. `usePaneBackgroundProjection`: Read-only `getQueryData` + `invalidateQueries` on change.
2. `ApprovalCard`: Mutation followed by `invalidateQueries`.
3. `QuestionnaireCard`: Submission followed by `invalidateQueries`.
4. `useThreadApprovals`: Decision followed by `invalidateQueries`.
5. `useAgentThreadController`: Compact/batch/stop mutations all use `invalidateQueries`, zero `setQueryData`.
6. `UnconfirmedExecutionsPanel`: Abandon mutation followed by `invalidateQueries`.
7. `chat-utils.ts:31`: Pure function for `chats` merge, does not touch `threads.snapshot`.
