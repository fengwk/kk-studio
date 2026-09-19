import type { BackendLong, CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

export type McpDiscoveryStatus = 'UNVERIFIED' | 'AVAILABLE' | 'FAILED'

/**
 * Platform MCP Server 公开安全表示。
 * 安全边界：仅暴露安全的元数据，绝不包含 URL、headers、bearer token 或凭据。
 */
export interface McpServerDTO {
  /** 唯一名（主键与路径身份，创建后不可变）：^[a-z][a-z0-9_]*$ 且 <=32 字符。 */
  name: string
  /** 公共启用状态。 */
  enabled: boolean
  /** 正整数毫秒超时。 */
  timeoutMillis: BackendLong
  /** 发现状态：UNVERIFIED、AVAILABLE、FAILED。 */
  discoveryStatus: McpDiscoveryStatus
  /** 当前 server 下已发现的工具数量。 */
  toolCount: number
  /** 当前配置的非负十进制字符串版本号；客户端每次更新/发现时必须回传。 */
  version: CatalogVersion
  /** 创建时间（UTC Instant）。 */
  createTime: InstantTimestamp
  /** 更新时间（UTC Instant）。 */
  updateTime: InstantTimestamp
}

/**
 * GET /api/ai/mcp-servers/{name}/config 显式配置响应 DTO。
 * 强制 Cache-Control: no-store，绝不放入通用 React Query 缓存或 LocalStorage。
 */
export interface McpServerConfigDTO {
  /** 唯一名（即路径身份）。 */
  name: string
  /** 当前配置的非负十进制字符串版本号。 */
  version: CatalogVersion
  /** Streamable HTTP endpoint URL。 */
  url: string
  /** 自定义请求 header（保留原始 ${VAR} 占位符，不回显解析后的凭据）。 */
  headers?: Record<string, string> | null
  /** 公共启用状态。 */
  enabled?: boolean
  /** 正整数毫秒超时。 */
  timeoutMillis?: BackendLong | null
}

/**
 * POST /api/ai/mcp-servers 请求体。
 */
export interface McpServerCreateDTO {
  /** 必填唯一名：^[a-z][a-z0-9_]*$ 且 <=32 字符，创建后不可变。 */
  name: string
  /** 必填 Streamable HTTP endpoint URL。 */
  url: string
  /** 自定义请求 header；可空。 */
  headers?: Record<string, string> | null
  /** 公共启用开关；可空时按 true 处理。 */
  enabled?: boolean
  /** 正整数毫秒超时；可空时取默认值。 */
  timeoutMillis?: BackendLong | null
}

/**
 * PUT /api/ai/mcp-servers/{name} 请求体。
 */
export interface McpServerUpdateDTO {
  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  expectedVersion: CatalogVersion
  /** 必填 Streamable HTTP endpoint URL。 */
  url: string
  /** 自定义请求 header；可空。 */
  headers?: Record<string, string> | null
  /** 公共启用开关；可空时按 true 处理。 */
  enabled?: boolean
  /** 正整数毫秒超时；可空时取默认值。 */
  timeoutMillis?: BackendLong | null
}
