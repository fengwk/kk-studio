import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** 持久化的 Chat 集合；agentName 为必填字段，但 Agent 被删除后可能过期失效。 */
export interface ChatDTO {
  id: string
  title: string | null
  agentName: string
  yoloEnabled: boolean
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface ChatCreateDTO {
  title?: string
  agentName: string
  yoloEnabled?: boolean
}

/** 部分更新：省略的字段保持不变。 */
export interface ChatUpdateDTO {
  title?: string | null
  agentName?: string
  yoloEnabled?: boolean
  expectedVersion: CatalogVersion
}
