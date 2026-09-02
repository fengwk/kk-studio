import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** 持久化的 Chat 集合；agentName 为必填字段，但 Agent 被删除后可能过期失效。 */
export interface ChatDTO {
  id: string
  title: string | null
  agentName: string
  /** 可选的默认分支 workspace path；null 表示未指定工作目录。 */
  workspacePath: string | null
  yoloEnabled: boolean
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface ChatCreateDTO {
  title?: string
  agentName: string
  /** 可选的默认分支 workspace path；省略或 null 表示未指定。 */
  workspacePath?: string | null
  yoloEnabled?: boolean
}

export interface ChatUpdateDTO {
  title?: string | null
  agentName?: string | null
  workspacePath?: string | null
  yoloEnabled?: boolean | null
  expectedVersion: CatalogVersion
}
