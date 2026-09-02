import type { BackendLong, CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** 不含 Bearer token 的 MCP Server 公开表示；token 是只写敏感字段，任何响应都不回显。 */
export interface McpServerDTO {
  /** Server 稳定 UUID（canonical 小写字符串形式）。 */
  id: string
  /** 唯一名：^[a-z][a-z0-9_]*$ 且 <=32 字符，创建后不可变。 */
  name: string
  /** Streamable HTTP MCP endpoint URL（<=2048 字符）。 */
  url: string
  /** 是否已配置 Bearer token（token 本身永不进入公开 DTO）。 */
  bearerTokenConfigured: boolean
  /** 正整数毫秒超时；连接、发现与工具调用共用。 */
  timeoutMillis: BackendLong
  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  version: CatalogVersion
  /** 创建时间（UTC Instant）。 */
  createTime: InstantTimestamp
  /** 更新时间（UTC Instant）。 */
  updateTime: InstantTimestamp
}

export interface McpServerCreateDTO {
  /** 必填唯一名：^[a-z][a-z0-9_]*$ 且 <=32 字符，创建后不可变。 */
  name: string
  /** 必填 Streamable HTTP MCP endpoint URL（<=2048 字符）。 */
  url: string
  /** 可选 Bearer token；null 表示匿名访问。 */
  bearerToken?: string | null
  /** 必填正整数毫秒超时；连接、发现与工具调用共用。 */
  timeoutMillis: BackendLong
}

/**
 * PUT /api/ai/mcp-servers/{id} 请求体。
 * bearerToken 是显式三态：null / undefined 保留现有值、空字符串清除、非空字符串替换。
 */
export interface McpServerUpdateDTO {
  /** 可选新 endpoint URL；null / undefined 表示不修改。 */
  url?: string | null
  /** Bearer token 三态更新。 */
  bearerToken?: string | null
  /** 可选新超时（正整数毫秒）；null / undefined 表示不修改。 */
  timeoutMillis?: BackendLong | null
  /** 必填非负十进制字符串；必须与当前 Server 版本一致。 */
  expectedVersion: CatalogVersion
}
