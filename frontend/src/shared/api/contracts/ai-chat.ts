import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** Persistent Chat collection; agentName is required but may be stale after Agent deletion. */
export interface ChatDTO {
  id: string
  title: string | null
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface ChatCreateDTO {
  title?: string
  agentName: string
  environmentName?: string | null
  yoloEnabled?: boolean
}

/** Partial update: omitted fields preserve; explicit null clears environmentName. */
export interface ChatUpdateDTO {
  title?: string | null
  agentName?: string
  environmentName?: string | null
  yoloEnabled?: boolean
  expectedVersion: CatalogVersion
}
