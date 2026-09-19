export const DEFAULT_TIMEOUT_MILLIS = 60000
export const URL_MAX_LENGTH = 2048
export const HEADER_NAME_MAX_LENGTH = 256
export const HEADER_VALUE_MAX_LENGTH = 8192
export const NAME_MAX_LENGTH = 32

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
  timeoutMillis: number
  error?: string
}

/**
 * 校验 MCP Server 唯一名：^[a-z][a-z0-9_]*$，长度 <= 32。
 */
export function validateMcpName(name: string): ValidationResult {
  if (!name || !name.trim()) {
    return { valid: false, error: 'Name must not be blank' }
  }
  if (!NAME_PATTERN.test(name)) {
    return {
      valid: false,
      error: 'Name must start with lowercase letter, contain only a-z, 0-9, _, and be <= 32 chars',
    }
  }
  if (name.length > NAME_MAX_LENGTH) {
    return {
      valid: false,
      error: `Name must not exceed ${NAME_MAX_LENGTH} characters`,
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
    return { valid: false, error: 'URL must not be blank' }
  }
  if (url !== url.trim()) {
    return { valid: false, error: 'URL must not contain surrounding whitespace' }
  }
  if (url.length > URL_MAX_LENGTH) {
    return { valid: false, error: `URL must not exceed ${URL_MAX_LENGTH} characters` }
  }
  try {
    const parsed = new URL(url)
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return { valid: false, error: 'URL scheme must be http or https' }
    }
    if (!parsed.hostname) {
      return { valid: false, error: 'URL must contain a valid host' }
    }
    if (parsed.username || parsed.password) {
      return { valid: false, error: 'URL must not contain user-info credentials' }
    }
  } catch {
    return { valid: false, error: 'URL format is invalid' }
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
    return { valid: false, headers: {}, error: 'Headers must be valid JSON' }
  }

  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { valid: false, headers: {}, error: 'Headers must be a JSON object' }
  }

  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(parsed)) {
    if (!key || !key.trim()) {
      return { valid: false, headers: {}, error: 'Header names must not be blank' }
    }
    if (key.length > HEADER_NAME_MAX_LENGTH) {
      return {
        valid: false,
        headers: {},
        error: `Header name must not exceed ${HEADER_NAME_MAX_LENGTH} characters`,
      }
    }
    if (hasIsoControl(key)) {
      return { valid: false, headers: {}, error: 'Header names must not contain control characters' }
    }
    if (typeof value !== 'string') {
      return { valid: false, headers: {}, error: `Header value for "${key}" must be a string` }
    }
    if (value.length > HEADER_VALUE_MAX_LENGTH) {
      return {
        valid: false,
        headers: {},
        error: `Header value for "${key}" must not exceed ${HEADER_VALUE_MAX_LENGTH} characters`,
      }
    }
    if (hasIsoControl(value)) {
      return {
        valid: false,
        headers: {},
        error: `Header value for "${key}" must not contain control characters`,
      }
    }
    // 绝不 trim 或修改 header 原始值
    result[key] = value
  }

  return { valid: true, headers: result }
}

/**
 * 校验超时毫秒：空值回退为 60000，非空时必须是正整数。
 */
export function validateTimeoutMillis(val: unknown): TimeoutValidationResult {
  if (val === undefined || val === null || val === '') {
    return { valid: true, timeoutMillis: DEFAULT_TIMEOUT_MILLIS }
  }
  const num = typeof val === 'number' ? val : Number(val)
  if (!Number.isFinite(num) || !Number.isInteger(num) || num <= 0) {
    return {
      valid: false,
      timeoutMillis: DEFAULT_TIMEOUT_MILLIS,
      error: 'Timeout must be a positive integer',
    }
  }
  return { valid: true, timeoutMillis: num }
}

/**
 * 比较两份 headers 字典是否完全一致。
 */
export function areHeadersEqual(
  a: Record<string, string> | null | undefined,
  b: Record<string, string> | null | undefined,
): boolean {
  const mapA = a ?? {}
  const mapB = b ?? {}
  const keysA = Object.keys(mapA)
  const keysB = Object.keys(mapB)
  if (keysA.length !== keysB.length) return false
  for (const k of keysA) {
    if (mapA[k] !== mapB[k]) return false
  }
  return true
}

export interface McpConfigFields {
  url: string
  headers: Record<string, string>
  enabled: boolean
  timeoutMillis: number
}

/**
 * 检查当前表单值与已加载配置是否存在未保存的语义修改。
 */
export function hasUnsavedChanges(current: McpConfigFields, loaded: McpConfigFields): boolean {
  if (current.url !== loaded.url) return true
  if (current.enabled !== loaded.enabled) return true
  if (current.timeoutMillis !== loaded.timeoutMillis) return true
  return !areHeadersEqual(current.headers, loaded.headers)
}
