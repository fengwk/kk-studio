import type {
  DaemonLspServerConfiguration,
  EnvironmentInstallConfigDTO,
  InstallOperatingSystem,
} from '@/shared/api/contracts/ai-environment'

const installerBase = 'https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon'

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

const sh = (value: string) => `'${value.replaceAll("'", "'\\''")}'`
const ps = (value: string) => `'${value.replaceAll("'", "''")}'`

function bashBlock(body: string): string {
  let delimiter = 'KK_STUDIO_INSTALL'
  while (body.split('\n').includes(delimiter)) delimiter += '_'
  return `bash <<'${delimiter}'\n${body}\n${delimiter}`
}

function unixCommand(
  action: 'install' | 'uninstall',
  config?: EnvironmentInstallConfigDTO,
  token?: string,
): string {
  const files = config
    ? `builtin printf '%s' ${sh(JSON.stringify(config.daemon))} > "$stage/daemon.json"
builtin printf '%s' ${sh(token!)} > "$stage/daemon.token"
chmod 600 "$stage/daemon.json" "$stage/daemon.token"
`
    : ''
  return bashBlock(`set +vx
set -euo pipefail
umask 077
stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
chmod 700 "$stage"
stage="$(cd -- "$stage" && pwd -P)"
${files}curl -fsSL ${sh(`${installerBase}/install.sh`)} -o "$stage/install.sh"
bash "$stage/install.sh" ${action}${config ? ` --config-file "$stage/daemon.json" --token-file "$stage/daemon.token"${config.javaHome ? ` --java-home ${sh(config.javaHome)}` : ''}` : ''}`)
}

/**
 * Windows staging：先建空目录并设置 current-SID 私有 ACL，再以 CreateNew 建空文件、
 * 逐个设置禁用继承的私有 ACL，最后才写入凭据。`CreateDirectory($path,$acl)` 仅
 * .NET Framework 提供，PowerShell 7 不可用，因此改走 Set-Acl。
 */
function windowsCommand(
  action: 'install' | 'uninstall',
  config?: EnvironmentInstallConfigDTO,
  token?: string,
): string {
  const staging = config
    ? `  $config = Join-Path $stage 'daemon.json'
  $token = Join-Path $stage 'daemon.token'
  foreach ($path in @($config, $token)) {
    $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew)
    $stream.Dispose()
    Set-KkPrivateAcl -Path $path
  }
  $utf8 = New-Object Text.UTF8Encoding($false)
  [IO.File]::WriteAllText($config, ${ps(JSON.stringify(config.daemon))}, $utf8)
  [IO.File]::WriteAllText($token, ${ps(token!)}, $utf8)
`
    : ''
  const parameters = config
    ? ` -ConfigFile $config -TokenFile $token${config.javaHome ? ` -JavaHome ${ps(config.javaHome)}` : ''}`
    : ''
  return `& {
function Set-KkPrivateAcl([string]$Path, [switch]$Directory) {
  $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
  $acl = if ($Directory) { [Security.AccessControl.DirectorySecurity]::new() } else { [Security.AccessControl.FileSecurity]::new() }
  $acl.SetOwner($owner)
  $acl.SetAccessRuleProtection($true, $false)
  $inheritance = if ($Directory) { 'ContainerInherit, ObjectInherit' } else { 'None' }
  $rule = [Security.AccessControl.FileSystemAccessRule]::new($owner, [Security.AccessControl.FileSystemRights]::FullControl, [Security.AccessControl.InheritanceFlags]$inheritance, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
  $acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $Path -AclObject $acl
}
Set-PSDebug -Off
$ErrorActionPreference = 'Stop'
$stage = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString('N'))
try {
  New-Item -ItemType Directory -Path $stage | Out-Null
  Set-KkPrivateAcl -Path $stage -Directory
${staging}  $installer = Join-Path $stage 'install.ps1'
  Invoke-WebRequest -UseBasicParsing -Uri ${ps(`${installerBase}/install.ps1`)} -OutFile $installer
  & powershell -NoProfile -ExecutionPolicy Bypass -File $installer ${action}${parameters}
  if ($LASTEXITCODE -ne 0) { throw 'Installer failed' }
}
catch {
  # Fixed message: never surface a staging exception that could quote credential-bearing source.
  throw 'Daemon command failed; review installer output and host prerequisites.'
}
finally {
  if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
}`
}

export function generateInstallCommand(input: EnvironmentInstallConfigDTO, token: string): string {
  const config = validateInstallConfig(input)
  nonblankText(token, 'registrationToken')
  return config.operatingSystem === 'windows'
    ? windowsCommand('install', config, token)
    : unixCommand('install', config, token)
}

export function generateUninstallCommand(os: InstallOperatingSystem): string {
  if (!(operatingSystems as readonly string[]).includes(os)) fail('operatingSystem')
  return os === 'windows' ? windowsCommand('uninstall') : unixCommand('uninstall')
}
