import type { IdeReply, IdeViewSummary } from '../contracts.js'

export interface ActiveStream {
  viewId: string
  bindingEpoch: string
  controller: AbortController
  closed: Promise<void>
  resolveClosed: () => void
  released: boolean
}

export interface ViewRecord {
  viewId: string
  /** Stable owner of the managed workspace; only this browser session may rebind it. */
  browserSessionId: string
  /** Current connection proof; changed by every successful binding CAS. */
  eventNodeId: string
  connectionId: string
  claimId: string
  bindingEpoch: string
  /** A binding CAS is in flight; data streams and lease proofs must not attach until it commits. */
  bindingPending?: boolean
  directory: string
  workspacePath: string
  workspaceOwned: boolean
  state: 'OPEN' | 'CLOSING'
  cleanupFailed: boolean
  createdAt: number
  lastProofAt: number | null
  expiresAt: number
  expiryTimer?: unknown
  streams: Set<ActiveStream>
  closePromise?: Promise<IdeReply>
  forceAllowed?: boolean
  nativeClosePending?: boolean
  sessionGeneration: number
}

export interface ClaimRecord {
  shape: string
  viewId: string
  bindingEpoch: string
}

/**
 * Complete visible IDE state frozen at the current committed revision.
 *
 * <p>Views are ordered by viewId and carry no heartbeat timestamps. A reservation is not part of
 * this snapshot, and a snapshot never waits for an in-flight binding operation.
 */
export interface IdeSnapshot {
  revision: string
  views: IdeViewSummary[]
}
