import type { BackendLong, CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'
import type { EnvironmentOperationDTO } from '@/shared/api/contracts/ai-environment'

export type McpConnectionType = 'remote' | 'local'
export type McpDiscoveryStatus = 'UNVERIFIED' | 'AVAILABLE' | 'FAILED'

/**
 * Platform MCP Server 公开安全表示。
 * 安全边界：仅暴露安全的元数据，绝不包含 URL、headers、env、command、cwd、bearer token 或完整配置 JSON。
 */
export interface McpServerDTO {
  /** Server 稳定 UUID（canonical 小写字符串形式）。 */
  id: string
  /** 唯一名：^[a-z][a-z0-9_]*$ 且 <=32 字符，创建后不可变。 */
  name: string
  /** 连接类型：remote 或 local。 */
  type: McpConnectionType
  /** 目标 Environment UUID（local 类型必填，remote 类型为 null）。 */
  environmentId: string | null
  /** 公共启用状态。 */
  enabled: boolean
  /** 正整数毫秒超时。 */
  timeoutMillis: BackendLong
  /** 发现状态：UNVERIFIED、AVAILABLE、FAILED。 */
  discoveryStatus: McpDiscoveryStatus
  /** 最近一次成功验证的配置版本（非负十进制字符串；未验证或变更后为 null）。 */
  discoveredVersion: CatalogVersion | null
  /** 当前 server 下持久工具数量（仅统计可用工具）。 */
  toolCount: number
  /** 当前配置的非负十进制字符串版本号；客户端每次更新/发现时必须回传。 */
  version: CatalogVersion
  /** 创建时间（UTC Instant）。 */
  createTime: InstantTimestamp
  /** 更新时间（UTC Instant）。 */
  updateTime: InstantTimestamp
}

/**
 * GET /api/ai/mcp-servers/{id}/config 显式配置响应 DTO。
 * 强制 Cache-Control: no-store，绝不放入通用 React Query 缓存或 LocalStorage。
 */
export interface McpServerConfigDTO {
  id: string
  name: string
  version: CatalogVersion
  configJson: string
}

/**
 * POST /api/ai/mcp-servers 请求体。
 */
export interface McpServerCreateDTO {
  name: string
  configJson: string
}

/**
 * PUT /api/ai/mcp-servers/{id} 请求体。
 */
export interface McpServerUpdateDTO {
  configJson: string
  expectedVersion: CatalogVersion
}

/**
 * POST /api/ai/mcp-servers/{id}/discover 请求体。
 */
export interface McpServerDiscoverDTO {
  expectedVersion: CatalogVersion
}

/**
 * POST /api/ai/mcp-servers/{id}/discover 响应体（HTTP 202 Accepted）。
 * Remote 返回同步完成后的 server 投影且 operation 为 null；
 * Local 返回异步 EnvironmentOperationDTO。
 */
export interface McpServerDiscoveryResponseDTO {
  server: McpServerDTO
  operation: EnvironmentOperationDTO | null
}
