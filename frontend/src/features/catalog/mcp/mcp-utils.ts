import { includesSearch } from '@/shared/lib/search-utils'
import { translate } from '@/shared/i18n'
import type { McpServerSummaryDTO } from '@/shared/api/generated/contracts'

export const DEFAULT_TIMEOUT_MILLIS = '60000'
const URL_MAX_LENGTH = 2048
const HEADER_NAME_MAX_LENGTH = 256
const HEADER_VALUE_MAX_LENGTH = 8192
const NAME_MAX_LENGTH = 32

const NAME_PATTERN = /^[a-z][a-z0-9_]*$/

function hasIsoControl(str: string): boolean {
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i)
    if ((code >= 0x00 && code <= 0x1f) || (code >= 0x7f && code <= 0x9f)) {
      return true
    }
  }
  return false
}

export interface ValidationResult {
  valid: boolean
  error?: string
}

export interface HeaderParseResult {
  valid: boolean
  headers: Record<string, string>
  error?: string
}

export interface TimeoutValidationResult {
  valid: boolean
  timeoutMillis: string
  error?: string
}

/**
 * 校验 MCP Server 唯一名：^[a-z][a-z0-9_]*$，长度 <= 32。
 */
export function validateMcpName(name: string): ValidationResult {
  if (!name || !name.trim()) {
    return { valid: false, error: translate('ai.mcp.validation.nameRequired') }
  }
  if (!NAME_PATTERN.test(name)) {
    return {
      valid: false,
      error: translate('ai.mcp.validation.namePattern'),
    }
  }
  if (name.length > NAME_MAX_LENGTH) {
    return {
      valid: false,
      error: translate('ai.mcp.validation.nameTooLong', { max: NAME_MAX_LENGTH }),
    }
  }
  return { valid: true }
}

/**
 * 校验 Streamable HTTP URL：
 * - 不允许首尾空格
 * - 必须为绝对 http 或 https
 * - 必须包含有效 host
 * - 不得包含 user-info 凭据
 * - 长度 <= 2048
 */
export function validateMcpUrl(url: string): ValidationResult {
  if (!url || !url.trim()) {
    return { valid: false, error: translate('ai.mcp.validation.urlRequired') }
  }
  if (url !== url.trim()) {
    return { valid: false, error: translate('ai.mcp.validation.urlWhitespace') }
  }
  if (url.length > URL_MAX_LENGTH) {
    return {
      valid: false,
      error: translate('ai.mcp.validation.urlTooLong', { max: URL_MAX_LENGTH }),
    }
  }
  try {
    const parsed = new URL(url)
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return { valid: false, error: translate('ai.mcp.validation.urlScheme') }
    }
    if (!parsed.hostname) {
      return { valid: false, error: translate('ai.mcp.validation.urlHost') }
    }
    if (parsed.username || parsed.password) {
      return { valid: false, error: translate('ai.mcp.validation.urlUserInfo') }
    }
  } catch {
    return { valid: false, error: translate('ai.mcp.validation.urlInvalid') }
  }
  return { valid: true }
}

/**
 * 解析并校验自定义 headers JSON。
 *
 * 严格边界：
 * - 必须是合法的 JSON Object
 * - 每个 key 必须非空且 <= 256 字符，不得含控制字符
 * - 每个 value 必须为 string 且 <= 8192 字符，不得含控制字符
 * - 拒绝非字符串类型（未知/非法值），绝不隐式 trim 或篡改 header 文本
 */
export function parseAndValidateHeaders(raw: string): HeaderParseResult {
  const trimmed = raw.trim()
  if (!trimmed) {
    return { valid: true, headers: {} }
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return { valid: false, headers: {}, error: translate('ai.mcp.validation.headersInvalidJson') }
  }

  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { valid: false, headers: {}, error: translate('ai.mcp.validation.headersNotObject') }
  }

  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(parsed)) {
    if (!key || !key.trim()) {
      return { valid: false, headers: {}, error: translate('ai.mcp.validation.headerNameRequired') }
    }
    if (key.length > HEADER_NAME_MAX_LENGTH) {
      return {
        valid: false,
        headers: {},
        error: translate('ai.mcp.validation.headerNameTooLong', { max: HEADER_NAME_MAX_LENGTH }),
      }
    }
    if (hasIsoControl(key)) {
      return {
        valid: false,
        headers: {},
        error: translate('ai.mcp.validation.headerNameControl'),
      }
    }
    if (typeof value !== 'string') {
      return {
        valid: false,
        headers: {},
        error: translate('ai.mcp.validation.headerValueString', { name: key }),
      }
    }
    if (value.length > HEADER_VALUE_MAX_LENGTH) {
      return {
        valid: false,
        headers: {},
        error: translate('ai.mcp.validation.headerValueTooLong', {
          name: key,
          max: HEADER_VALUE_MAX_LENGTH,
        }),
      }
    }
    if (hasIsoControl(value)) {
      return {
        valid: false,
        headers: {},
        error: translate('ai.mcp.validation.headerValueControl', { name: key }),
      }
    }
    // 绝不 trim 或修改 header 原始值
    result[key] = value
  }

  return { valid: true, headers: result }
}

/** 列表按名称过滤；保持服务端返回顺序，不额外排序。 */
export function filterMcpServers(
  servers: McpServerSummaryDTO[],
  search: string,
): McpServerSummaryDTO[] {
  return servers.filter((server) => includesSearch(server.name, search))
}

/**
 * 校验超时毫秒：空值回退为 60000，非空时必须是正整数。
 */
export function validateTimeoutMillis(val: unknown): TimeoutValidationResult {
  if (val === undefined || val === null || val === '') {
    return { valid: true, timeoutMillis: DEFAULT_TIMEOUT_MILLIS }
  }
  if (typeof val !== 'string' || !/^[1-9][0-9]*$/.test(val) || BigInt(val) > 9223372036854775807n) {
    return {
      valid: false,
      timeoutMillis: DEFAULT_TIMEOUT_MILLIS,
      error: translate('ai.mcp.validation.timeoutPositive'),
    }
  }
  return { valid: true, timeoutMillis: val }
}
