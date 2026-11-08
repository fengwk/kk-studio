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

/**
 * Studio HTTP(S) origin：无 userinfo/path/query/fragment，端口合法，
 * 与 DaemonConfigCodec.origin 的规范化结果一致。
 */
function origin(value: unknown): string {
  const raw = nonblankText(value, 'daemonConfig.studioUrl')
  if (/[\s\\]/.test(raw) || [...raw].some((char) => char.codePointAt(0)! > 0x7e)) {
    fail('daemonConfig.studioUrl')
  }
  const scheme = /^https?:\/\//i.exec(raw)
  if (scheme === null) fail('daemonConfig.studioUrl')
  const rest = raw.slice(scheme[0].length)
  const cut = rest.search(/[/?#]/)
  const authority = cut === -1 ? rest : rest.slice(0, cut)
  const path = cut === -1 ? '' : rest.slice(cut)
  if (authority.length === 0 || authority.includes('@') || authority.endsWith(':')) {
    fail('daemonConfig.studioUrl')
  }
  if (path !== '' && path !== '/') fail('daemonConfig.studioUrl')
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return fail('daemonConfig.studioUrl')
  }
  if (url.hostname.length === 0 || (url.protocol !== 'http:' && url.protocol !== 'https:')) {
    fail('daemonConfig.studioUrl')
  }
  if (url.port === '0') fail('daemonConfig.studioUrl')
  return url.origin
}

/**
 * 卡片代理：null/空白表示显式直连；非空只接受无认证的 http://host:port，
 * 拒绝 userinfo、path、query、fragment 与非法端口。
 */
function proxy(value: unknown): string | null {
  const raw = stringOrNull(value, 'daemonConfig.proxyUrl')
  if (raw === null || raw.trim().length === 0) return null
  if (/[\s\\]/.test(raw) || [...raw].some((char) => char.codePointAt(0)! > 0x7e)) {
    fail('daemonConfig.proxyUrl')
  }
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return fail('daemonConfig.proxyUrl')
  }
  if (
    url.protocol !== 'http:' ||
    url.username !== '' ||
    url.password !== '' ||
    url.search !== '' ||
    url.hash !== '' ||
    (url.pathname !== '' && url.pathname !== '/') ||
    url.hostname.length === 0 ||
    url.port === ''
  ) {
    fail('daemonConfig.proxyUrl')
  }
  const port = Number(url.port)
  if (!Number.isInteger(port) || port < 1 || port > 65535) fail('daemonConfig.proxyUrl')
  return `http://${url.host}`
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
