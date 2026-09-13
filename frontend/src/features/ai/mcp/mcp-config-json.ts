/**
 * MCP Server 配置 JSON 严格解析、重复键防护、校验与模板工具。
 * 对齐后端 McpConfigParser.java 规范，安全边界：绝不在错误消息中泄露敏感凭证或变量值。
 */

const REMOTE_ALLOWED_KEYS = new Set(['type', 'url', 'headers', 'enabled', 'timeoutMillis'])
const LOCAL_ALLOWED_KEYS = new Set([
  'type',
  'environmentId',
  'command',
  'cwd',
  'env',
  'enabled',
  'timeoutMillis',
])

const CANONICAL_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

const WHOLE_VAR_PATTERN = /^\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}$/
const EMBEDDED_VAR_PATTERN =
  /(?:\$\{[a-zA-Z_][a-zA-Z0-9_]*\}|\$[a-zA-Z_][a-zA-Z0-9_]*|%[a-zA-Z_][a-zA-Z0-9_]*%)/

export interface McpValidationResult {
  valid: boolean
  error?: string
  parsed?: Record<string, unknown>
}

/**
 * 依赖无关的严格 JSON 重复键防护。
 * 遍历 JSON 文本，在每一个对象层级跟踪已出现的键（包含 \uXXXX 等等效转义键），
 * 一旦发现重复键立即抛出异常；若末尾存在多余非空白字符同样拒绝。
 */
export function assertNoDuplicateJsonKeys(json: string): void {
  if (!json || !json.trim()) {
    throw new Error('JSON text must not be blank')
  }

  let pos = 0

  function skipWhitespace(): void {
    while (pos < json.length) {
      const ch = json[pos]
      if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') {
        pos++
      } else {
        break
      }
    }
  }

  function scanString(): string {
    const start = pos
    pos++ // skip opening quote '"'
    while (pos < json.length) {
      const ch = json[pos]
      if (ch === '\\') {
        if (pos + 1 >= json.length) {
          throw new Error(`Unterminated escape sequence in JSON at position ${pos}`)
        }
        pos += 2
        continue
      }
      if (ch === '"') {
        pos++ // skip closing quote
        return json.slice(start, pos)
      }
      if (ch.charCodeAt(0) < 0x20) {
        throw new Error(`Unescaped control character in JSON string at position ${pos}`)
      }
      pos++
    }
    throw new Error('Unterminated string in JSON')
  }

  function parseObject(): void {
    pos++ // skip '{'
    skipWhitespace()
    const seenKeys = new Set<string>()

    if (pos < json.length && json[pos] === '}') {
      pos++ // skip '}'
      return
    }

    while (pos < json.length) {
      skipWhitespace()
      if (json[pos] !== '"') {
        throw new Error(`Expected string key in object at position ${pos}`)
      }
      const rawKeyToken = scanString()
      let decodedKey: string
      try {
        decodedKey = JSON.parse(rawKeyToken) as string
      } catch {
        throw new Error(`Invalid JSON string key at position ${pos}`)
      }

      if (seenKeys.has(decodedKey)) {
        throw new Error(`Duplicate key "${decodedKey}" in JSON object`)
      }
      seenKeys.add(decodedKey)

      skipWhitespace()
      if (pos >= json.length || json[pos] !== ':') {
        throw new Error(`Expected ':' after key "${decodedKey}" at position ${pos}`)
      }
      pos++ // skip ':'

      parseValue()

      skipWhitespace()
      if (pos < json.length && json[pos] === ',') {
        pos++ // skip ','
        skipWhitespace()
        if (pos < json.length && json[pos] === '}') {
          throw new Error(`Trailing comma in object at position ${pos}`)
        }
        continue
      }
      if (pos < json.length && json[pos] === '}') {
        pos++ // skip '}'
        return
      }
      throw new Error(`Expected ',' or '}' at position ${pos}`)
    }
    throw new Error('Unterminated object in JSON')
  }

  function parseArray(): void {
    pos++ // skip '['
    skipWhitespace()
    if (pos < json.length && json[pos] === ']') {
      pos++ // skip ']'
      return
    }

    while (pos < json.length) {
      parseValue()
      skipWhitespace()
      if (pos < json.length && json[pos] === ',') {
        pos++ // skip ','
        skipWhitespace()
        if (pos < json.length && json[pos] === ']') {
          throw new Error(`Trailing comma in array at position ${pos}`)
        }
        continue
      }
      if (pos < json.length && json[pos] === ']') {
        pos++ // skip ']'
        return
      }
      throw new Error(`Expected ',' or ']' at position ${pos}`)
    }
    throw new Error('Unterminated array in JSON')
  }

  function parseNumber(): void {
    const start = pos
    if (json[pos] === '-') {
      pos++
    }
    if (pos >= json.length) {
      throw new Error(`Invalid number at position ${start}`)
    }
    if (json[pos] === '0') {
      pos++
    } else if (json[pos] >= '1' && json[pos] <= '9') {
      while (pos < json.length && json[pos] >= '0' && json[pos] <= '9') {
        pos++
      }
    } else {
      throw new Error(`Invalid number at position ${start}`)
    }

    if (pos < json.length && json[pos] === '.') {
      pos++
      if (pos >= json.length || json[pos] < '0' || json[pos] > '9') {
        throw new Error(`Invalid fractional part in number at position ${start}`)
      }
      while (pos < json.length && json[pos] >= '0' && json[pos] <= '9') {
        pos++
      }
    }

    if (pos < json.length && (json[pos] === 'e' || json[pos] === 'E')) {
      pos++
      if (pos < json.length && (json[pos] === '+' || json[pos] === '-')) {
        pos++
      }
      if (pos >= json.length || json[pos] < '0' || json[pos] > '9') {
        throw new Error(`Invalid exponent in number at position ${start}`)
      }
      while (pos < json.length && json[pos] >= '0' && json[pos] <= '9') {
        pos++
      }
    }
  }

  function parseLiteral(expected: string): void {
    if (json.startsWith(expected, pos)) {
      pos += expected.length
    } else {
      throw new Error(`Unexpected token at position ${pos}, expected ${expected}`)
    }
  }

  function parseValue(): void {
    skipWhitespace()
    if (pos >= json.length) {
      throw new Error('Unexpected end of JSON input')
    }
    const ch = json[pos]
    if (ch === '{') {
      parseObject()
    } else if (ch === '[') {
      parseArray()
    } else if (ch === '"') {
      scanString()
    } else if (ch === 't') {
      parseLiteral('true')
    } else if (ch === 'f') {
      parseLiteral('false')
    } else if (ch === 'n') {
      parseLiteral('null')
    } else if (ch === '-' || (ch >= '0' && ch <= '9')) {
      parseNumber()
    } else {
      throw new Error(`Unexpected character '${ch}' at position ${pos}`)
    }
  }

  skipWhitespace()
  parseValue()
  skipWhitespace()
  if (pos < json.length) {
    throw new Error(`Unexpected trailing characters at position ${pos}`)
  }
}

/**
 * 校验 cwd 是否为目标 OS 绝对路径或单一 ${VAR}。
 */
export function isTargetAbsoluteCwd(cwd: string): boolean {
  if (WHOLE_VAR_PATTERN.test(cwd)) {
    return true
  }
  if (EMBEDDED_VAR_PATTERN.test(cwd)) {
    return false
  }
  if (cwd.startsWith('/')) {
    return true
  }
  if (cwd.length >= 3) {
    const driveLetter = cwd.charAt(0)
    const separator = cwd.charAt(2)
    const asciiLetter =
      (driveLetter >= 'a' && driveLetter <= 'z') || (driveLetter >= 'A' && driveLetter <= 'Z')
    if (asciiLetter && cwd.charAt(1) === ':' && (separator === '/' || separator === '\\')) {
      return true
    }
  }
  return isWindowsUncPath(cwd)
}

function nextPathSeparator(value: string, fromIndex: number): number {
  for (let i = fromIndex; i < value.length; i++) {
    const ch = value.charAt(i)
    if (ch === '/' || ch === '\\') {
      return i
    }
  }
  return -1
}

function isWindowsUncPath(cwd: string): boolean {
  if (!cwd.startsWith('\\\\')) {
    return false
  }
  const remainder = cwd.substring(2)
  const serverEnd = nextPathSeparator(remainder, 0)
  if (serverEnd <= 0 || serverEnd === remainder.length - 1) {
    return false
  }
  const shareEnd = nextPathSeparator(remainder, serverEnd + 1)
  const share =
    shareEnd < 0
      ? remainder.substring(serverEnd + 1)
      : remainder.substring(serverEnd + 1, shareEnd)
  return share.trim().length > 0
}

/**
 * 严格校验 MCP 配置 JSON 字符串。
 */
export function validateMcpConfigJson(json: string): McpValidationResult {
  if (!json || !json.trim()) {
    return { valid: false, error: 'Configuration JSON must not be blank' }
  }

  try {
    assertNoDuplicateJsonKeys(json)
  } catch (err) {
    return { valid: false, error: err instanceof Error ? err.message : 'Invalid JSON' }
  }

  let root: unknown
  try {
    root = JSON.parse(json)
  } catch (err) {
    return { valid: false, error: err instanceof Error ? err.message : 'Invalid JSON' }
  }

  if (typeof root !== 'object' || root === null || Array.isArray(root)) {
    return { valid: false, error: 'Configuration must be a JSON object' }
  }

  const obj = root as Record<string, unknown>
  const type = obj.type
  if (
    typeof type !== 'string' ||
    !type.trim() ||
    (type.trim() !== 'remote' && type.trim() !== 'local')
  ) {
    return { valid: false, error: 'type is required and must be "remote" or "local"' }
  }
  const connectionType = type.trim() as 'remote' | 'local'

  if ('enabled' in obj && obj.enabled !== undefined) {
    if (typeof obj.enabled !== 'boolean') {
      return { valid: false, error: 'enabled must be a boolean' }
    }
  }

  if ('timeoutMillis' in obj && obj.timeoutMillis !== undefined) {
    const timeout = obj.timeoutMillis
    if (
      typeof timeout !== 'number' ||
      !Number.isInteger(timeout) ||
      !Number.isSafeInteger(timeout) ||
      timeout <= 0
    ) {
      return { valid: false, error: 'timeoutMillis must be a positive integer within safe range' }
    }
  }

  if (connectionType === 'remote') {
    const extraKeys = Object.keys(obj).filter((k) => !REMOTE_ALLOWED_KEYS.has(k))
    if (extraKeys.length > 0) {
      return {
        valid: false,
        error: `Unexpected fields for remote mcp config: ${extraKeys.join(', ')}`,
      }
    }

    if (!('url' in obj) || typeof obj.url !== 'string' || !obj.url.trim()) {
      return { valid: false, error: 'url is required and must not be blank' }
    }
    const url = obj.url
    if (url !== url.trim()) {
      return { valid: false, error: 'url must not contain surrounding whitespace' }
    }
    if (url.length > 2048) {
      return { valid: false, error: 'url must not exceed 2048 characters' }
    }
    let parsedUrl: URL
    try {
      parsedUrl = new URL(url)
    } catch {
      return { valid: false, error: 'url format is invalid' }
    }
    if (parsedUrl.protocol !== 'http:' && parsedUrl.protocol !== 'https:') {
      return { valid: false, error: 'url scheme must be http or https' }
    }
    if (!parsedUrl.hostname) {
      return { valid: false, error: 'url must contain a valid host' }
    }
    if (parsedUrl.username || parsedUrl.password) {
      return { valid: false, error: 'url must not contain user-info credentials' }
    }
    if (parsedUrl.hash) {
      return { valid: false, error: 'url must not contain a fragment' }
    }

    if ('headers' in obj && obj.headers !== undefined && obj.headers !== null) {
      if (typeof obj.headers !== 'object' || Array.isArray(obj.headers)) {
        return { valid: false, error: 'headers must be a JSON object' }
      }
      for (const [k, v] of Object.entries(obj.headers as Record<string, unknown>)) {
        if (!k || !k.trim()) {
          return { valid: false, error: 'headers keys must not be blank' }
        }
        if (typeof v !== 'string') {
          return { valid: false, error: `headers values must be strings: key "${k}"` }
        }
      }
    }
  } else {
    const extraKeys = Object.keys(obj).filter((k) => !LOCAL_ALLOWED_KEYS.has(k))
    if (extraKeys.length > 0) {
      return {
        valid: false,
        error: `Unexpected fields for local mcp config: ${extraKeys.join(', ')}`,
      }
    }

    if (
      !('environmentId' in obj) ||
      typeof obj.environmentId !== 'string' ||
      !obj.environmentId.trim()
    ) {
      return { valid: false, error: 'environmentId is required for local connection type' }
    }
    const envId = obj.environmentId.trim()
    if (!CANONICAL_UUID_PATTERN.test(envId)) {
      return { valid: false, error: 'environmentId must be a canonical lowercase dashed UUID' }
    }

    if (!('command' in obj) || !Array.isArray(obj.command) || obj.command.length === 0) {
      return { valid: false, error: 'command is required and must be a non-empty array' }
    }
    for (let i = 0; i < obj.command.length; i++) {
      const cmd = obj.command[i]
      if (typeof cmd !== 'string' || !cmd.trim()) {
        return { valid: false, error: `command elements must be non-blank strings at index ${i}` }
      }
    }

    if (!('cwd' in obj) || typeof obj.cwd !== 'string' || !obj.cwd.trim()) {
      return { valid: false, error: 'cwd is required and must not be blank' }
    }
    const cwd = obj.cwd
    if (cwd !== cwd.trim()) {
      return { valid: false, error: 'cwd must not contain surrounding whitespace' }
    }
    if (cwd.length > 2048) {
      return { valid: false, error: 'cwd must not exceed 2048 characters' }
    }
    const hasControlChar = Array.from(cwd).some((ch) => {
      const code = ch.charCodeAt(0)
      return code <= 0x1f || (code >= 0x7f && code <= 0x9f)
    })
    if (hasControlChar) {
      return { valid: false, error: 'cwd must not contain control characters' }
    }
    if (!isTargetAbsoluteCwd(cwd)) {
      return {
        valid: false,
        error:
          'cwd must be an absolute Unix, Windows drive-rooted, or Windows UNC path, or whole ${VAR}',
      }
    }

    if ('env' in obj && obj.env !== undefined && obj.env !== null) {
      if (typeof obj.env !== 'object' || Array.isArray(obj.env)) {
        return { valid: false, error: 'env must be a JSON object' }
      }
      for (const [k, v] of Object.entries(obj.env as Record<string, unknown>)) {
        if (!k || !k.trim()) {
          return { valid: false, error: 'env keys must not be blank' }
        }
        if (typeof v !== 'string') {
          return { valid: false, error: `env values must be strings: key "${k}"` }
        }
      }
    }
  }

  return { valid: true, parsed: obj }
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
    return false
  }
  if (Array.isArray(a) !== Array.isArray(b)) return false
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return false
    for (let i = 0; i < a.length; i++) {
      if (!deepEqual(a[i], b[i])) return false
    }
    return true
  }
  const aObj = a as Record<string, unknown>
  const bObj = b as Record<string, unknown>
  const aKeys = Object.keys(aObj)
  const bKeys = Object.keys(bObj)
  if (aKeys.length !== bKeys.length) return false
  for (const key of aKeys) {
    if (!Object.prototype.hasOwnProperty.call(bObj, key)) return false
    if (!deepEqual(aObj[key], bObj[key])) return false
  }
  return true
}

/**
 * 语义等价比对：在严格无重复键的前提下比对两个 JSON，仅空白/换行格式差异被视为等价。
 */
export function isSemanticConfigEqual(aJson: string, bJson: string): boolean {
  if (aJson === bJson) return true
  if (!aJson || !bJson) return false
  try {
    assertNoDuplicateJsonKeys(aJson)
    assertNoDuplicateJsonKeys(bJson)
    const a = JSON.parse(aJson)
    const b = JSON.parse(bJson)
    return deepEqual(a, b)
  } catch {
    return false
  }
}

/**
 * 严格格式化 JSON：防止静默合并重复键，先检测后格式化。
 */
export function formatMcpConfigJson(json: string): string {
  assertNoDuplicateJsonKeys(json)
  const parsed = JSON.parse(json)
  return JSON.stringify(parsed, null, 2)
}

/**
 * 安全格式化 JSON：如果存在语法或重复键错误，保留原文本不抛出。
 */
export function formatMcpConfigJsonSafely(json: string): string {
  try {
    return formatMcpConfigJson(json)
  } catch {
    return json
  }
}

/**
 * 提取草稿 JSON 中的连接类型（remote 或 local）。
 */
export function extractDraftConnectionType(json: string): 'remote' | 'local' | null {
  try {
    assertNoDuplicateJsonKeys(json)
    const parsed = JSON.parse(json)
    if (
      parsed &&
      typeof parsed === 'object' &&
      (parsed.type === 'remote' || parsed.type === 'local')
    ) {
      return parsed.type
    }
  } catch {
    if (/"type"\s*:\s*"local"/.test(json)) return 'local'
    if (/"type"\s*:\s*"remote"/.test(json)) return 'remote'
  }
  return null
}

/**
 * 提取草稿 JSON 中的 environmentId。
 */
export function extractDraftEnvironmentId(json: string): string | null {
  try {
    assertNoDuplicateJsonKeys(json)
    const parsed = JSON.parse(json)
    if (parsed && typeof parsed === 'object' && typeof parsed.environmentId === 'string') {
      return parsed.environmentId
    }
  } catch {
    const match = /"environmentId"\s*:\s*"([^"]*)"/.exec(json)
    if (match) return match[1]
  }
  return null
}

/**
 * 在保持单一权威 JSON 的前提下，回写 Local JSON 中的 environmentId。
 */
export function updateLocalEnvironmentIdInJson(json: string, environmentId: string): string {
  try {
    assertNoDuplicateJsonKeys(json)
    const obj = JSON.parse(json)
    if (typeof obj === 'object' && obj !== null && !Array.isArray(obj)) {
      obj.environmentId = environmentId
      return JSON.stringify(obj, null, 2)
    }
  } catch {
    if (/"environmentId"\s*:\s*"[^"]*"/.test(json)) {
      return json.replace(/"environmentId"\s*:\s*"[^"]*"/, `"environmentId": "${environmentId}"`)
    }
  }
  return json
}

export const REMOTE_CONFIG_TEMPLATE = JSON.stringify(
  {
    type: 'remote',
    url: 'https://example.com/mcp',
    headers: {
      Authorization: '${MCP_AUTHORIZATION}',
    },
    enabled: true,
    timeoutMillis: 60000,
  },
  null,
  2,
)

export function createLocalConfigTemplate(environmentId?: string): string {
  return JSON.stringify(
    {
      type: 'local',
      environmentId: environmentId || '00000000-0000-0000-0000-000000000000',
      command: ['my-mcp-server', '--stdio'],
      cwd: '/workspace/project',
      env: {
        API_KEY: '${PROJECT_API_KEY}',
      },
      enabled: true,
      timeoutMillis: 60000,
    },
    null,
    2,
  )
}
