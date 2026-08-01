import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** Persistent Chat collection; defaultAgentId is required but may be stale after Agent deletion. */
export interface ChatDTO {
  id: string
  title: string | null
  defaultAgentId: string
  defaultEnvironmentName: string | null
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface ChatCreateDTO {
  title?: string
  defaultAgentId: string
  defaultEnvironmentName?: string
}

/** Partial update: omitted fields preserve; explicit null clears defaultEnvironmentName. */
export interface ChatUpdateDTO {
  title?: string | null
  defaultAgentId?: string
  defaultEnvironmentName?: string | null
  expectedVersion: CatalogVersion
}
