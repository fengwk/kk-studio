import type { BackendDateTime, CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** Flat Session query projection; Sessions are only created as a side effect of Thread bootstrap. */
export interface HarnessSessionDTO {
  sessionId: string
  title: string | null
  createTime: BackendDateTime
  /** Observable last-entry time; not a stored column. */
  updateTime: BackendDateTime
}

/** Persistent Chat collection; defaultAgentId may be stale after Agent deletion. */
export interface ChatDTO {
  id: string
  title: string | null
  defaultAgentId: string | null
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface ChatCreateDTO {
  title?: string
  defaultAgentId?: string
}

/** Partial update: null preserves; title must be non-blank when supplied; blank defaultAgentId clears it. */
export interface ChatUpdateDTO {
  title?: string | null
  defaultAgentId?: string | null
  expectedVersion: CatalogVersion
}
