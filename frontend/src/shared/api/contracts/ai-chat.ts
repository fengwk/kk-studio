import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** Persistent Chat collection; defaultAgentId is required but may be stale after Agent deletion. */
export interface ChatDTO {
  id: string
  title: string | null
  defaultAgentId: string
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface ChatCreateDTO {
  title?: string
  defaultAgentId: string
}

/** Partial update: omitted defaultAgentId preserves; a supplied value must be non-blank. */
export interface ChatUpdateDTO {
  title?: string | null
  defaultAgentId?: string
  expectedVersion: CatalogVersion
}
