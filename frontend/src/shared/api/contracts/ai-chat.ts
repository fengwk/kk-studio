import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** 持久化的 Chat 集合；agentName 为必填字段，但 Agent 被删除后可能过期失效。 */
export interface ChatDTO {
  id: string
  title: string | null
  agentName: string
  /** 可选的默认分支 Environment 逻辑路由名称；新空面板/线程草稿以此为起点，用户发送前可更改或清空。 */
  environmentName: string | null
  yoloEnabled: boolean
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface ChatCreateDTO {
  title?: string
  agentName: string
  /** 可选的默认分支 Environment 逻辑路由名称；省略为 null。 */
  environmentName?: string | null
  yoloEnabled?: boolean
}

/** 部分更新：省略的字段保持不变；environmentName 显式传 null 表示清空默认环境。 */
export interface ChatUpdateDTO {
  title?: string | null
  agentName?: string
  environmentName?: string | null
  yoloEnabled?: boolean
  expectedVersion: CatalogVersion
}
