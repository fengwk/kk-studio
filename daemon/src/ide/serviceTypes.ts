import type { IdeReply } from '../contracts.js'

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
  documentId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  bindingEpoch: string
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
  reply: IdeReply
  viewId: string
  bindingEpoch: string
}
