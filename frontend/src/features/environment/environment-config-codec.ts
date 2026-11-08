import type {
  DaemonConfigDTO,
  DaemonLspConfiguration,
  DaemonLspServerConfiguration,
} from '@/shared/api/generated/contracts'

/**
 * 卡片配置校验失败。只携带字段路径；message 与异常都不包含输入值，
 * 组件据此渲染字段级 i18n 文案。
 */
export class EnvironmentConfigValidationError extends Error {
  readonly field: string

  constructor(field: string) {
    super(`Invalid ${field}`)
    this.name = 'EnvironmentConfigValidationError'
    this.field = field
  }
}

function fail(field: string): never {
  throw new EnvironmentConfigValidationError(field)
}

// Java Character.isISOControl (C0 + C1) plus the JSON/JS line separators.
// eslint-disable-next-line no-control-regex
const controls = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/
const configFields = ['studioUrl', 'bashExecutable', 'lsp', 'proxyUrl']
const serverFields = ['command', 'extensions', 'rootMarkers', 'firstMatchMarkers']
const serverIdPattern = /^[A-Za-z0-9_.-]+$/
const canonicalDecimal = /^(0|[1-9]\d*)$/

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasUnknownField(value: Record<string, unknown>, allowed: string[]): boolean {
  return Object.keys(value).some((key) => !allowed.includes(key))
}

function stringOrNull(value: unknown, field: string): string | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string') fail(field)
  return value
}

function nonblankText(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim().length === 0 || controls.test(value)) fail(field)
  return value
}

const hostnamePattern =
  /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\.?$/
const portPattern = /^[0-9]{1,5}$/

/** 解析显式端口；保留原始 authority（含 :80 与 host 大小写），与 Java codec 一致。 */
function parsePort(value: string, field: string): number {
  if (!portPattern.test(value)) fail(field)
  const port = Number(value)
  if (port < 1 || port > 65535) fail(field)
  return port
}

function validateBracketedHost(host: string, field: string): void {
  if (!host.includes(':')) fail(field)
  let url: URL
  try {
    url = new URL(`http://[${host}]`)
  } catch {
    return fail(field)
  }
  if (!url.hostname.startsWith('[')) fail(field)
}

function validateHostname(host: string, field: string): void {
  if (!hostnamePattern.test(host)) fail(field)
  // WHATWG URL 会把 127.0.0.999 当作域名；Java URI.getHost 将其视为非法 IPv4，
  // 因此对纯数字点分 host 额外校验四段 0..255。
  if (host.includes('.') && /^[0-9.]+$/.test(host)) {
    const octets = host.split('.')
    if (
      octets.length !== 4 ||
      octets.some((octet) => !/^[0-9]{1,3}$/.test(octet) || Number(octet) > 255)
    ) {
      fail(field)
    }
  }
}

/** 严格解析 authority：拒绝空 userinfo、空端口和非法 host/port。 */
function parseAuthority(value: string, field: string): number | null {
  if (value.length === 0 || value.includes('@')) fail(field)
  let host: string
  let portText: string | null = null
  let bracketed = false
  if (value.startsWith('[')) {
    const close = value.indexOf(']')
    if (close <= 1) fail(field)
    host = value.slice(1, close)
    bracketed = true
    const suffix = value.slice(close + 1)
    if (suffix !== '') {
      if (!suffix.startsWith(':')) fail(field)
      portText = suffix.slice(1)
    }
  } else {
    if (value.includes('[') || value.includes(']')) fail(field)
    const colon = value.indexOf(':')
    if (colon !== -1) {
      if (colon !== value.lastIndexOf(':')) fail(field)
      host = value.slice(0, colon)
      portText = value.slice(colon + 1)
    } else {
      host = value
    }
  }
  if (host.length === 0) fail(field)
  if (bracketed) validateBracketedHost(host, field)
  else validateHostname(host, field)
  return portText === null ? null : parsePort(portText, field)
}

/**
 * 只按原始文本解析 HTTP(S) URL，不依赖 WHATWG URL 的默认端口/主机大小写归一化。
 * 调用方仅接受空 path 或单个 /，因此空 ? / # 段也不会被吞掉。
 */
function parseHttpUrl(
  value: unknown,
  field: string,
  schemes: readonly string[],
): { scheme: string; authority: string; port: number | null } {
  const raw = nonblankText(value, field)
  if (/[\s\\]/.test(raw) || [...raw].some((char) => char.codePointAt(0)! > 0x7e)) fail(field)
  const schemeMatch = /^([A-Za-z][A-Za-z0-9+.-]*):\/\//.exec(raw)
  if (schemeMatch === null) fail(field)
  const scheme = schemeMatch[1]!.toLowerCase()
  if (!schemes.includes(scheme)) fail(field)
  const rest = raw.slice(schemeMatch[0].length)
  const cut = rest.search(/[/?#]/)
  const authority = cut === -1 ? rest : rest.slice(0, cut)
  const suffix = cut === -1 ? '' : rest.slice(cut)
  if (suffix !== '' && suffix !== '/') fail(field)
  return { scheme, authority, port: parseAuthority(authority, field) }
}

/**
 * Studio HTTP(S) origin：无 userinfo/path/query/fragment，端口合法。
 * 与 DaemonConfigCodec.origin 一样只小写 scheme，保留 Backend 已接受的显式端口和 host 大小写，
 * 避免用户仅编辑备注/代理时产生无意的 studioUrl 变更。
 */
function origin(value: unknown): string {
  const parsed = parseHttpUrl(value, 'daemonConfig.studioUrl', ['http', 'https'])
  return `${parsed.scheme}://${parsed.authority}`
}

/**
 * 卡片代理：null/空白表示显式直连；非空只接受无认证且带显式端口的 http://host:port。
 * 端口按原始 authority 解析，因此 http://proxy:80 不会被 URL.port 的空字符串误判为无端口。
 */
function proxy(value: unknown): string | null {
  const raw = stringOrNull(value, 'daemonConfig.proxyUrl')
  if (raw === null || raw.trim().length === 0) return null
  const parsed = parseHttpUrl(raw, 'daemonConfig.proxyUrl', ['http'])
  if (parsed.port === null) fail('daemonConfig.proxyUrl')
  return `http://${parsed.authority}`
}

function bashExecutable(value: unknown): string | null {
  const raw = stringOrNull(value, 'daemonConfig.bashExecutable')
  if (raw === null) return null
  if (raw.trim().length === 0 || controls.test(raw)) fail('daemonConfig.bashExecutable')
  return raw
}

function stringList(
  value: unknown,
  path: string,
  required: boolean,
  kind: 'plain' | 'extension' | 'marker',
): string[] {
  if (value === null || value === undefined) {
    if (required) fail(path)
    return []
  }
  if (!Array.isArray(value)) fail(path)
  if (value.length === 0) {
    if (required) fail(path)
    return []
  }
  return value.map((item, index) => {
    const field = `${path}[${index}]`
    if (typeof item !== 'string' || item.trim().length === 0 || controls.test(item)) fail(field)
    if (kind === 'extension') {
      const extension = item.trim().toLowerCase()
      if (
        extension.length < 2 ||
        !extension.startsWith('.') ||
        extension.includes('/') ||
        extension.includes('\\')
      ) {
        fail(field)
      }
      return extension
    }
    if (kind === 'marker') {
      const marker = item.trim()
      const segments = marker.replaceAll('\\', '/').split('/')
      const traversal = segments.some((part) => part === '..' || part === '.' || part === '')
      if (marker.startsWith('/') || marker.startsWith('\\') || marker.includes(':') || traversal) {
        fail(field)
      }
      return marker
    }
    return item
  })
}

/** LSP 声明与 DaemonConfigCodec 一致：command 必填，extensions/markers 缺省归一化为空数组。 */
function lspConfiguration(value: unknown): DaemonLspConfiguration | null {
  if (value === null || value === undefined) return null
  if (!isPlainObject(value) || hasUnknownField(value, ['servers'])) fail('daemonConfig.lsp')
  const nodes = value.servers
  if (!isPlainObject(nodes) || Object.keys(nodes).length === 0) {
    fail('daemonConfig.lsp.servers')
  }
  const servers: Record<string, DaemonLspServerConfiguration> = Object.create(null)
  for (const [id, node] of Object.entries(nodes)) {
    const path = `daemonConfig.lsp.servers.${id}`
    if (!serverIdPattern.test(id) || !isPlainObject(node) || hasUnknownField(node, serverFields)) {
      fail(path)
    }
    servers[id] = {
      command: stringList(node.command, `${path}.command`, true, 'plain'),
      extensions: stringList(node.extensions, `${path}.extensions`, false, 'extension'),
      rootMarkers: stringList(node.rootMarkers, `${path}.rootMarkers`, false, 'marker'),
      firstMatchMarkers: stringList(
        node.firstMatchMarkers,
        `${path}.firstMatchMarkers`,
        false,
        'marker',
      ),
    }
  }
  return { servers }
}

/**
 * 返回与后端相同的完整 canonical 配置：origin 收敛、空白代理为直连、
 * extensions 小写、markers 去空白、可选数组补空。
 */
export function validateDaemonConfig(config: DaemonConfigDTO): DaemonConfigDTO {
  if (!isPlainObject(config) || hasUnknownField(config, configFields)) fail('daemonConfig')
  return {
    studioUrl: origin(config.studioUrl),
    bashExecutable: bashExecutable(config.bashExecutable),
    lsp: lspConfiguration(config.lsp),
    proxyUrl: proxy(config.proxyUrl),
  }
}

/** 名称创建后不可变；创建请求只发送去首尾空白后的合法名称。 */
export function validateEnvironmentName(raw: string): string {
  if (raw.trim().length === 0) fail('name')
  if (raw !== raw.trim()) fail('name')
  if (raw.includes('/')) fail('name')
  if (raw.length > 64) fail('name')
  if (controls.test(raw)) fail('name')
  return raw
}

/** 备注可为空；保存时去首尾空白，拒绝控制字符与超过 512 字符的文本。 */
export function validateEnvironmentNote(raw: string): string | null {
  if (controls.test(raw)) fail('note')
  const note = raw.trim()
  if (note.length === 0) return null
  if (note.length > 512) fail('note')
  return note
}

/** runtime 版本与卡片版本都是 canonical 非负十进制字符串，比较必须避免 Number 精度损失。 */
export function compareEnvironmentVersions(left: string, right: string): number | null {
  if (!canonicalDecimal.test(left) || !canonicalDecimal.test(right)) return null
  const a = BigInt(left)
  const b = BigInt(right)
  return a === b ? 0 : a < b ? -1 : 1
}
