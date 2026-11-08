import { url } from '@/shared/api/client'
import {
  DAEMON_OPERATING_SYSTEM_VALUES,
  type DaemonOperatingSystem,
} from '@/shared/api/generated/contracts'

/** 安装命令参数校验失败。只携带字段路径，不包含命令注入内容。 */
export class InstallCommandError extends Error {
  readonly field: string

  constructor(field: string) {
    super(`Invalid ${field}`)
    this.name = 'InstallCommandError'
    this.field = field
  }
}

function fail(field: string): never {
  throw new InstallCommandError(field)
}

// Java Character.isISOControl (C0 + C1) plus the JSON/JS line separators.
// eslint-disable-next-line no-control-regex
const controls = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/

/**
 * 可安装的宿主系统：Daemon 还能运行在 WSL，但没有独立的安装/卸载脚本；
 * 安装入口只服务生成值中除 WSL 以外的系统。
 */
export type InstallOperatingSystem = Exclude<DaemonOperatingSystem, 'wsl'>
export const INSTALL_OPERATING_SYSTEMS: readonly InstallOperatingSystem[] =
  DAEMON_OPERATING_SYSTEM_VALUES.filter(
    (operatingSystem): operatingSystem is InstallOperatingSystem => operatingSystem !== 'wsl',
  )

export function isInstallOperatingSystem(value: unknown): value is InstallOperatingSystem {
  return (INSTALL_OPERATING_SYSTEMS as readonly unknown[]).includes(value)
}

export function detectInstallOS(platform: string): InstallOperatingSystem {
  return /win/i.test(platform) ? 'windows' : /mac/i.test(platform) ? 'macos' : 'linux'
}

function nonblankText(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim().length === 0 || controls.test(value)) fail(field)
  return value
}

/** bash `$'...'` 与 PowerShell 单引号，都把已编码 URL 当作字面量。 */
const sh = (value: string) => `$'${value.replaceAll('\\', '\\\\').replaceAll("'", "\\'")}'`
const ps = (value: string) => `'${value.replaceAll("'", "''")}'`

/** 严格 origin：无 userinfo、path、query、fragment。 */
function downloadOrigin(value: string): string {
  const raw = nonblankText(value, 'downloadOrigin')
  if (/[\s\\]/.test(raw) || [...raw].some((char) => char.codePointAt(0)! > 0x7e)) {
    fail('downloadOrigin')
  }
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return fail('downloadOrigin')
  }
  if (
    url.origin !== raw ||
    (url.protocol !== 'http:' && url.protocol !== 'https:') ||
    url.username !== '' ||
    url.password !== '' ||
    url.port === '0'
  ) {
    fail('downloadOrigin')
  }
  return url.origin
}

function scriptUrl(originValue: string, path: string): string {
  return `${downloadOrigin(originValue)}${path}`
}

/** 一行：curl 失败或远端脚本失败都返回非零。 */
function unixCommand(url: string): string {
  return `(set -o pipefail; curl -fsSL ${sh(url)} | bash)`
}

/** 一行：下载前关闭调试输出；变量与错误策略在局部作用域隔离。 */
function windowsCommand(url: string): string {
  const quoted = ps(url)
  return `& { Set-PSDebug -Off; $ErrorActionPreference='Stop'; $script = (Invoke-WebRequest -UseBasicParsing -ErrorAction Stop -Uri ${quoted}).Content; if ([string]::IsNullOrEmpty($script)) { throw 'Empty installer' }; & ([scriptblock]::Create($script)) }`
}

/**
 * 安装命令只携带卡片 UUID、一次性 code 与目标 OS；完整配置和新 token 由服务端在兑换脚本时下发。
 */
export function generateInstallCommand(
  origin: string,
  environmentId: string,
  os: InstallOperatingSystem,
  code: string,
): string {
  nonblankText(environmentId, 'environmentId')
  nonblankText(code, 'installCode')
  if (!isInstallOperatingSystem(os)) fail('operatingSystem')
  const downloadUrl = scriptUrl(
    origin,
    url('GET /api/platform/environments/{environmentId}/install-script', {
      path: { environmentId },
      query: { code, operatingSystem: os },
    }),
  )
  return os === 'windows' ? windowsCommand(downloadUrl) : unixCommand(downloadUrl)
}

/** 卸载命令只定位当前卡片与目标 OS；不读取、不携带任何凭据。 */
export function generateUninstallCommand(
  origin: string,
  environmentId: string,
  os: InstallOperatingSystem,
): string {
  nonblankText(environmentId, 'environmentId')
  if (!isInstallOperatingSystem(os)) fail('operatingSystem')
  const downloadUrl = scriptUrl(
    origin,
    url('GET /api/platform/environments/{environmentId}/uninstall-script', {
      path: { environmentId },
      query: { operatingSystem: os },
    }),
  )
  return os === 'windows' ? windowsCommand(downloadUrl) : unixCommand(downloadUrl)
}
