import type {
  DaemonLspServerConfiguration,
  EnvironmentInstallConfigDTO,
  InstallOperatingSystem,
} from '@/shared/api/contracts/ai-environment'

/**
 * 安装设置校验失败。只携带字段路径；`message` 不含输入值，供非 UI 调用方使用，
 * 组件通过 `field` 渲染字段级 i18n 文案而不直接展示英文 message。
 */
export class InstallConfigError extends Error {
  readonly field: string

  constructor(field: string) {
    super(`Invalid ${field}`)
    this.name = 'InstallConfigError'
    this.field = field
  }
}

function fail(field: string): never {
  throw new InstallConfigError(field)
}

// Java Character.isISOControl (C0 + C1) plus the JSON/JS line separators.
// eslint-disable-next-line no-control-regex
const controls = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/
const operatingSystems = ['linux', 'macos', 'windows'] as const
const topLevelFields = ['operatingSystem', 'javaHome', 'daemon']
const daemonFields = ['studioUrl', 'note', 'bashExecutable', 'lsp']
const serverFields = ['command', 'extensions', 'rootMarkers', 'firstMatchMarkers']
const serverIdPattern = /^[A-Za-z0-9_.-]+$/

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasUnknownField(value: Record<string, unknown>, allowed: string[]): boolean {
  return Object.keys(value).some(key => !allowed.includes(key))
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

/** Studio HTTP(S) origin：无 userinfo/path/query/fragment，端口合法，与共享 codec 一致。 */
function origin(value: unknown): string {
  const raw = nonblankText(value, 'daemon.studioUrl')
  if (/[\s\\]/.test(raw) || [...raw].some(char => char.codePointAt(0)! > 0x7e)) fail('daemon.studioUrl')
  const scheme = /^https?:\/\//i.exec(raw)
  if (scheme === null) fail('daemon.studioUrl')
  const rest = raw.slice(scheme[0].length)
  const cut = rest.search(/[/?#]/)
  const authority = cut === -1 ? rest : rest.slice(0, cut)
  const path = cut === -1 ? '' : rest.slice(cut)
  if (authority.length === 0 || authority.includes('@') || authority.endsWith(':')) {
    fail('daemon.studioUrl')
  }
  if (path !== '' && path !== '/') fail('daemon.studioUrl')
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return fail('daemon.studioUrl')
  }
  if (url.hostname.length === 0 || (url.protocol !== 'http:' && url.protocol !== 'https:')) {
    fail('daemon.studioUrl')
  }
  if (url.port === '0') fail('daemon.studioUrl')
  return url.origin
}

function note(value: unknown): string | null {
  const raw = stringOrNull(value, 'daemon.note')
  if (raw === null) return null
  if (controls.test(raw)) fail('daemon.note')
  const stripped = raw.trim()
  if (stripped.length === 0 || stripped.length > 512) fail('daemon.note')
  return stripped
}

function bashExecutable(value: unknown): string | null {
  const raw = stringOrNull(value, 'daemon.bashExecutable')
  if (raw === null) return null
  if (raw.trim().length === 0 || controls.test(raw)) fail('daemon.bashExecutable')
  return raw
}

/** 与后端 `EnvironmentInstallConfigs.validate` 相同的绝对路径与占位符规则。 */
function javaHome(value: unknown, os: InstallOperatingSystem): string | null {
  const home = stringOrNull(value, 'installConfig.javaHome')
  if (home === null) return null
  const windows = os === 'windows'
  const absolute = windows
    ? /^[A-Za-z]:[\\/]/.test(home) || /^\\\\[^\\/]+[\\/][^\\/]+/.test(home)
    : home.startsWith('/')
  const pathPart = /^[A-Za-z]:/.test(home) ? home.slice(2) : home
  const invalidWindowsCharacters = windows && [...pathPart].some(char => '<>:"|?*'.includes(char))
  if (!absolute || invalidWindowsCharacters || home.trim().length === 0 || controls.test(home)
    || home.includes('$') || home.includes('%') || home.includes('~') || home !== home.trim()) {
    fail('installConfig.javaHome')
  }
  return home
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
      if (extension.length < 2 || !extension.startsWith('.')
        || extension.includes('/') || extension.includes('\\')) fail(field)
      return extension
    }
    if (kind === 'marker') {
      const marker = item.trim()
      const segments = marker.replaceAll('\\', '/').split('/')
      const traversal = segments.some(part => part === '..' || part === '.' || part === '')
      if (marker.startsWith('/') || marker.startsWith('\\') || marker.includes(':') || traversal) {
        fail(field)
      }
      return marker
    }
    return item
  })
}

function lspConfiguration(
  value: unknown,
): { servers: Record<string, DaemonLspServerConfiguration> } | null {
  if (value === null || value === undefined) return null
  if (!isPlainObject(value) || Object.keys(value).some(key => key !== 'servers')) fail('daemon.lsp')
  const nodes = value.servers
  if (!isPlainObject(nodes) || Object.keys(nodes).length === 0) fail('daemon.lsp.servers')
  const servers: Record<string, DaemonLspServerConfiguration> = Object.create(null)
  for (const [id, node] of Object.entries(nodes)) {
    const path = `daemon.lsp.servers.${id}`
    if (!serverIdPattern.test(id) || !isPlainObject(node) || hasUnknownField(node, serverFields)) {
      fail(path)
    }
    servers[id] = {
      command: stringList(node.command, `${path}.command`, true, 'plain'),
      extensions: stringList(node.extensions, `${path}.extensions`, true, 'extension'),
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
 * 返回规范化配置：studioUrl 收敛为 origin，note 去空白，extensions 转小写，
 * markers 去空白，与服务端保存结果一致。
 */
export function validateInstallConfig(
  config: EnvironmentInstallConfigDTO,
): EnvironmentInstallConfigDTO {
  if (!isPlainObject(config) || hasUnknownField(config, topLevelFields)) fail('installConfig')
  const os = config.operatingSystem
  if (typeof os !== 'string' || !(operatingSystems as readonly string[]).includes(os)) {
    fail('installConfig.operatingSystem')
  }
  const home = javaHome(config.javaHome, os as InstallOperatingSystem)
  if (!isPlainObject(config.daemon) || hasUnknownField(config.daemon, daemonFields)) fail('daemon')
  return {
    operatingSystem: os as InstallOperatingSystem,
    javaHome: home,
    daemon: {
      studioUrl: origin(config.daemon.studioUrl),
      note: note(config.daemon.note),
      bashExecutable: bashExecutable(config.daemon.bashExecutable),
      lsp: lspConfiguration(config.daemon.lsp),
    },
  }
}

export function detectInstallOS(platform: string): InstallOperatingSystem {
  return /win/i.test(platform) ? 'windows' : /mac/i.test(platform) ? 'macos' : 'linux'
}

/**
 * URL 已百分号编码。bash `$'...'` 把编码结果里的单引号写成 `\'`，`%` 保持连续字面量。
 * PowerShell 单引号内的单引号写成 `''`。
 */
const sh = (value: string) => `$'${value.replaceAll('\\', '\\\\').replaceAll("'", "\\'")}'`
const ps = (value: string) => `'${value.replaceAll("'", "''")}'`

/** 当前页面 origin：无 userinfo/path/query/fragment。与 studioUrl 无关。 */
function downloadOrigin(value: string): string {
  const raw = nonblankText(value, 'downloadOrigin')
  if (/[\s\\]/.test(raw) || [...raw].some(char => char.codePointAt(0)! > 0x7e)) fail('downloadOrigin')
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return fail('downloadOrigin')
  }
  if (url.origin !== raw || (url.protocol !== 'http:' && url.protocol !== 'https:')
    || url.username !== '' || url.password !== '' || url.port === '0') {
    fail('downloadOrigin')
  }
  return url.origin
}

function scriptUrl(originValue: string, path: string): string {
  return `${downloadOrigin(originValue)}${path}`
}

/** 一行：pipefail 下 curl 失败不会被空 bash 吃掉，远端脚本状态原样返回。 */
function unixCommand(url: string): string {
  return `(set -o pipefail; curl -fsSL ${sh(url)} | bash)`
}

/**
 * 一行：先完整下载（PS5.1/7 的 -UseBasicParsing + ErrorAction Stop），再在内存里执行。
 * 不在客户端写暂存文件。
 */
function windowsCommand(url: string): string {
  const quoted = ps(url)
  return `$ErrorActionPreference='Stop'; $script = (Invoke-WebRequest -UseBasicParsing -ErrorAction Stop -Uri ${quoted}).Content; if ([string]::IsNullOrEmpty($script)) { throw 'Empty installer' }; & ([scriptblock]::Create($script))`
}

/** 安装脚本由服务端按已保存配置生成；URL 只带 5 分钟 code，不带长期 token。 */
export function generateInstallCommand(
  origin: string,
  environmentId: string,
  os: InstallOperatingSystem,
  code: string,
): string {
  nonblankText(environmentId, 'environmentId')
  nonblankText(code, 'installCode')
  if (!(operatingSystems as readonly string[]).includes(os)) fail('operatingSystem')
  const url = scriptUrl(
    origin,
    `/api/harness/environments/${encodeURIComponent(environmentId)}/install?code=${encodeURIComponent(code)}`,
  )
  return os === 'windows' ? windowsCommand(url) : unixCommand(url)
}

export function generateUninstallCommand(os: InstallOperatingSystem, origin: string): string {
  if (!(operatingSystems as readonly string[]).includes(os)) fail('operatingSystem')
  const url = scriptUrl(origin, `/api/harness/environments/uninstall/${encodeURIComponent(os)}`)
  return os === 'windows' ? windowsCommand(url) : unixCommand(url)
}
