import type {
  EnvironmentInstallConfigDTO,
  InstallOperatingSystem,
} from '@/shared/api/contracts/ai-environment'

const installerBase = 'https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon'
// Explicitly reject shell/JSON control characters, not just whitespace.
// eslint-disable-next-line no-control-regex
const controls = /[\u0000-\u001f\u007f]/

function fail(field: string): never {
  // Never include values: validation can run on credential-bearing input.
  throw new Error(`Invalid ${field}`)
}

function text(value: unknown, field: string, max = 4096): string {
  if (typeof value !== 'string' || !value.trim() || controls.test(value) || value.length > max) fail(field)
  return value
}

export function validateInstallConfig(config: EnvironmentInstallConfigDTO): EnvironmentInstallConfigDTO {
  if (!config || typeof config !== 'object' || !config.daemon
    || typeof config.daemon !== 'object'
    || Object.keys(config).some(key => !['operatingSystem', 'javaHome', 'daemon'].includes(key))
    || Object.keys(config.daemon).some(key => !['studioUrl', 'note', 'bashExecutable', 'lsp'].includes(key))) fail('installConfig')
  if (!['linux', 'macos', 'windows'].includes(config.operatingSystem)) fail('operatingSystem')
  const raw = text(config.daemon.studioUrl, 'daemon.studioUrl')
  // URL alone would silently normalize backslashes, whitespace and paths.
  if (!/^https?:\/\/[^/?#\\]+\/?$/.test(raw)) fail('daemon.studioUrl')
  const authority = raw.replace(/^https?:\/\//, '').replace(/\/$/, '')
  if (authority.includes('@') || authority.endsWith(':') || /\s/.test(authority)) fail('daemon.studioUrl')
  let url: URL
  try { url = new URL(raw) } catch { return fail('daemon.studioUrl') }
  if (!url.hostname || url.username || url.password || url.search || url.hash || url.port === '0') fail('daemon.studioUrl')
  const optional = (value: string | null | undefined, field: string, max = 4096) =>
    value == null ? null : typeof value !== 'string' ? fail(field)
      : !value.trim() ? null : text(value.trim(), field, max)
  const javaHome = optional(config.javaHome, 'javaHome')
  if (javaHome && !(config.operatingSystem === 'windows'
    ? /^(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+)/.test(javaHome)
    : javaHome.startsWith('/'))) fail('javaHome')
  if (javaHome && /\$\{|%[^%]+%/.test(javaHome)) fail('javaHome')
  const note = optional(config.daemon.note, 'daemon.note', 512)
  const bashExecutable = optional(config.daemon.bashExecutable, 'daemon.bashExecutable')
  const lsp = config.daemon.lsp
  if (lsp != null) {
    if (Object.keys(lsp).some(key => key !== 'servers')) fail('daemon.lsp')
    if (new TextEncoder().encode(JSON.stringify(lsp)).byteLength > 65536) fail('daemon.lsp size')
    if (!lsp.servers || typeof lsp.servers !== 'object' || Array.isArray(lsp.servers)
      || !Object.keys(lsp.servers).length || Object.keys(lsp.servers).length > 64) fail('daemon.lsp.servers')
    for (const [id, server] of Object.entries(lsp.servers)) {
      if (!/^[A-Za-z0-9_.-]+$/.test(id) || !server || typeof server !== 'object'
        || Object.keys(server).some(key => !['command', 'extensions', 'rootMarkers', 'firstMatchMarkers'].includes(key))) fail('daemon.lsp server')
      for (const field of ['command', 'extensions', 'rootMarkers', 'firstMatchMarkers'] as const) {
        const list = server[field]
        if (field !== 'command' && list == null) continue
        if (!Array.isArray(list) || list.length > 256 || (field === 'command' && !list.length)) fail(`daemon.lsp.${field}`)
        for (const item of list) {
          text(item, `daemon.lsp.${field}`)
          if (field === 'extensions' && (!item.startsWith('.') || /[\\/]/.test(item))) fail('daemon.lsp.extensions')
          if (field.endsWith('Markers') && (/^(?:[\\/]|[A-Za-z]:)/.test(item)
            || item.split(/[\\/]/).some(part => part === '..'))) fail(`daemon.lsp.${field}`)
        }
      }
    }
  }
  return { operatingSystem: config.operatingSystem, javaHome,
    daemon: { studioUrl: url.origin, note, bashExecutable, ...(lsp == null ? {} : { lsp }) } }
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

function unixCommand(action: 'install' | 'uninstall', config?: EnvironmentInstallConfigDTO, token?: string): string {
  const files = config ? `builtin printf '%s' ${sh(JSON.stringify(config.daemon))} > "$stage/daemon.json"
builtin printf '%s' ${sh(token!)} > "$stage/daemon.token"
chmod 600 "$stage/daemon.json" "$stage/daemon.token"
` : ''
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

function windowsCommand(action: 'install' | 'uninstall', config?: EnvironmentInstallConfigDTO, token?: string): string {
  return `& {
Set-PSDebug -Off
$ErrorActionPreference = 'Stop'
$stage = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString())
try {
  $acl = New-Object Security.AccessControl.DirectorySecurity
  $acl.SetAccessRuleProtection($true, $false)
  $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
  $acl.SetOwner($sid)
  $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
  $acl.AddAccessRule($rule)
  [IO.Directory]::CreateDirectory($stage, $acl) | Out-Null
  $utf8 = New-Object Text.UTF8Encoding($false)
${config ? `  [IO.File]::WriteAllText((Join-Path $stage 'daemon.json'), ${ps(JSON.stringify(config.daemon))}, $utf8)
  [IO.File]::WriteAllText((Join-Path $stage 'daemon.token'), ${ps(token!)}, $utf8)
` : ''}  $installer = Join-Path $stage 'install.ps1'
  Invoke-WebRequest -UseBasicParsing -Uri ${ps(`${installerBase}/install.ps1`)} -OutFile $installer
  & powershell -NoProfile -ExecutionPolicy Bypass -File $installer ${action}${config ? ` -ConfigFile (Join-Path $stage 'daemon.json') -TokenFile (Join-Path $stage 'daemon.token')${config.javaHome ? ` -JavaHome ${ps(config.javaHome)}` : ''}` : ''}
  if ($LASTEXITCODE -ne 0) { throw 'Installer failed' }
} catch {
  # Do not rethrow a file-write error with credential-bearing source text.
  throw 'Daemon command failed; review installer output and host prerequisites.'
} finally {
  if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
}`
}

export function generateInstallCommand(input: EnvironmentInstallConfigDTO, token: string): string {
  const config = validateInstallConfig(input)
  text(token, 'registrationToken')
  return config.operatingSystem === 'windows'
    ? windowsCommand('install', config, token) : unixCommand('install', config, token)
}

export function generateUninstallCommand(os: InstallOperatingSystem): string {
  if (!['linux', 'macos', 'windows'].includes(os)) fail('operatingSystem')
  return os === 'windows' ? windowsCommand('uninstall') : unixCommand('uninstall')
}
