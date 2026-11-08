import { execFile } from 'node:child_process'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { homedir, tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'

import type { DaemonConfig } from './contracts.js'
import { getOutboundNetwork } from './network.js'
import { ensurePrivateDirectory, writePrivateFile } from './native/index.js'
import { DAEMON_VERSION_PATTERN, currentPlatformKey } from './release.js'
import { isCanonicalUuid } from './protocol/uuid.js'
import type { UpdateOutcomeLike } from './runtime-ports.js'

const exec = promisify(execFile)
const MAX_INSTALLER_BYTES = 1024 * 1024
const PERMIT_TIMEOUT_SECONDS = 120

/**
 * 独立更新作业的 Unix runner。
 *
 * <p>作业拥有自己的 trap：无论成功、失败、等待回执超时还是被终止，都删除下载的安装脚本、runner、
 * ready/permit 标记与整个临时作业目录；不保留按 operationId 累积的日志或历史目录。持久结果只由
 * 安装器在卡片目录写入固定的 `last-operation.json`。
 */
const UNIX_RUNNER = `#!/bin/bash
set -eu
work=$1
installer=$2
envRoot=$3
operationId=$4
targetVersion=$5
shift 5
timeout=\${KK_UPDATE_PERMIT_TIMEOUT_SECONDS:-120}
case "$timeout" in ''|*[!0-9]*) timeout=120 ;; esac
if [ "$timeout" -gt 3600 ]; then timeout=120; fi

# 子安装器在取得操作锁之前失败时不会写卡片结果；runner 在作业最后写一条受限的本次失败记录，
# 让仍在运行的旧 Daemon 能按 operationId 观察到结果。若安装器已写入匹配记录则绝不覆盖。
# 只写固定字段，不重试、不备份，也不携带任何配置或凭据。
record_failure() {
  [ -d "$envRoot" ] || return 0
  [ ! -L "$envRoot" ] || return 0
  [[ $operationId =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || return 0
  [[ $targetVersion =~ ^[0-9A-Za-z._+-]{1,64}$ ]] || return 0
  current="$envRoot/last-operation.json"
  if [ -f "$current" ] && grep -Fq '"operationId":"'"$operationId"'"' "$current" 2>/dev/null; then
    return 0
  fi
  time=$(( $(date +%s) * 1000 ))
  json=$(printf '{"operationId":"%s","type":"update","targetVersion":"%s","phase":"job","result":"failed","time":%s,"errorCode":"installer"}' \
    "$operationId" "$targetVersion" "$time")
  (( \${#json} <= 4096 )) || return 0
  tmp="$envRoot/.last-operation.json.tmp.$$"
  if ! printf '%s\n' "$json" >"$tmp" 2>/dev/null; then rm -f "$tmp"; return 0; fi
  if ! chmod 600 "$tmp" 2>/dev/null || ! mv -f "$tmp" "$current" 2>/dev/null; then
    rm -f "$tmp"
    return 0
  fi
}

cleanup() {
  code=$?
  trap - EXIT INT TERM
  rm -rf "$work"
  exit "$code"
}
trap cleanup EXIT INT TERM
: > "$work/ready"
i=0
until [ -f "$work/permit" ]; do
  if [ "$i" -ge "$timeout" ]; then
    echo "update receipt permit timed out" >&2
    record_failure
    exit 1
  fi
  sleep 1
  i=$((i + 1))
done
set +e
bash "$installer" "$@"
code=$?
set -e
if [ "$code" -ne 0 ]; then
  record_failure
  exit "$code"
fi
`

/** 在线更新只读取安装上下文与卡片代理；配置内容属于安装器/控制通道，不在这里解释。 */
export interface InstallerConfig {
  environmentId: string
  proxyUrl: string | null
  configPath: string
  tokenPath: string
  distributionDir: string
}

function ps(value: string): string {
  return `'${value.replaceAll("'", "''")}'`
}

function encoded(script: string): string {
  return Buffer.from(script, 'utf16le').toString('base64')
}

export interface InstallerDependencies {
  network: Pick<ReturnType<typeof getOutboundNetwork>, 'withResponse'>
  platform: NodeJS.Platform
  arch: string
  home: string
  launch(file: string, args: string[], signal: AbortSignal): Promise<void>
}

/** The service-manager client is short-lived; the installed OS job outlives it. */
export async function launchInstallerJob(file: string, args: string[], signal: AbortSignal): Promise<void> {
  await exec(file, args, { signal, timeout: 15_000, maxBuffer: 64 * 1024, windowsHide: true })
}

/**
 * 把解析后的 Daemon 配置收窄成安装上下文。
 *
 * <p>`environmentId` 与 `proxyUrl` 是冻结的 DaemonConfig 字段；该适配器同时校验它们在运行时确实存在，
 * 因此缺少卡片身份时在线更新显式失败，而不是更新到其它环境的目录。
 */
export function toInstallerConfig(config: DaemonConfig): InstallerConfig {
  const candidate = config as DaemonConfig & {
    environmentId?: unknown
    proxyUrl?: unknown
  }
  if (typeof candidate.environmentId !== 'string' || !isCanonicalUuid(candidate.environmentId)) {
    throw new Error('the daemon configuration has no canonical environment identity')
  }
  if (candidate.proxyUrl !== null && typeof candidate.proxyUrl !== 'string') {
    throw new Error('the daemon configuration has no proxy decision')
  }
  return {
    environmentId: candidate.environmentId,
    proxyUrl: candidate.proxyUrl,
    configPath: config.configPath,
    tokenPath: config.tokenPath,
    distributionDir: config.distributionDir,
  }
}

function equalPath(platform: NodeJS.Platform, a: string, b: string): boolean {
  const left = resolve(a)
  const right = resolve(b)
  return platform === 'win32'
    ? left.toLowerCase() === right.toLowerCase()
    : left === right
}

function managedEnvironmentRoot(home: string, environmentId: string): string {
  return join(home, '.kk-studio', 'environments', environmentId)
}

function requireManagedInstallation(config: InstallerConfig, home: string, platform: NodeJS.Platform): void {
  const root = managedEnvironmentRoot(home, config.environmentId)
  if (!equalPath(platform, config.configPath, join(root, 'daemon.json')) ||
      !equalPath(platform, config.tokenPath, join(root, 'daemon.token')) ||
      !equalPath(platform, config.distributionDir, join(root, 'lib'))) {
    throw new Error('online update requires the official managed installation')
  }
}

function installerUrl(platform: NodeJS.Platform, targetVersion: string): string {
  const script = platform === 'win32' ? 'install.ps1' : 'install.sh'
  return `https://raw.githubusercontent.com/fengwk/kk-studio/v${targetVersion}/scripts/daemon/${script}`
}

async function downloadInstaller(
  url: string,
  destination: string,
  signal: AbortSignal,
  network: InstallerDependencies['network'],
): Promise<void> {
  const timeout = AbortSignal.any([signal, AbortSignal.timeout(60_000)])
  await network.withResponse(url, { method: 'GET', redirect: 'error' }, timeout, async response => {
    if (response.statusCode !== 200) {
      throw new Error('official installer download failed')
    }
    const chunks: Buffer[] = []
    let size = 0
    for await (const chunk of response.body) {
      const data = Buffer.from(chunk as Uint8Array)
      size += data.length
      if (size > MAX_INSTALLER_BYTES) {
        throw new Error('official installer exceeds size limit')
      }
      chunks.push(data)
    }
    if (size === 0) {
      throw new Error('official installer is empty')
    }
    await writePrivateFile(destination, Buffer.concat(chunks))
  })
}

function unixInvocation(
  workDirectory: string,
  installer: string,
  environmentRoot: string,
  config: InstallerConfig,
  targetVersion: string,
  operationId: string,
  home: string,
  environment: NodeJS.ProcessEnv,
): string[] {
  const proxy = config.proxyUrl ?? ''
  const invoke = ['env', '-i', `HOME=${home}`, `PATH=${environment.PATH ?? '/usr/bin:/bin'}`]
  // 卡片 proxyUrl 是本次更新作业的唯一代理决定；null 时显式直连，不读取宿主服务环境。
  for (const name of ['https_proxy', 'HTTPS_PROXY', 'http_proxy', 'HTTP_PROXY']) {
    invoke.push(`${name}=${proxy}`)
  }
  invoke.push('no_proxy=', 'NO_PROXY=')
  if (environment.XDG_RUNTIME_DIR) {
    invoke.push(`XDG_RUNTIME_DIR=${environment.XDG_RUNTIME_DIR}`)
  }
  if (environment.DBUS_SESSION_BUS_ADDRESS) {
    invoke.push(`DBUS_SESSION_BUS_ADDRESS=${environment.DBUS_SESSION_BUS_ADDRESS}`)
  }
  invoke.push(`KK_STUDIO_UPDATE_OPERATION_ID=${operationId}`)
  if (environment.KK_UPDATE_PERMIT_TIMEOUT_SECONDS?.match(/^[0-9]{1,4}$/)) {
    invoke.push(`KK_UPDATE_PERMIT_TIMEOUT_SECONDS=${environment.KK_UPDATE_PERMIT_TIMEOUT_SECONDS}`)
  }
  invoke.push('bash', join(workDirectory, 'run.sh'), workDirectory, installer,
    environmentRoot, operationId, targetVersion)
  invoke.push('update', '--environment-id', config.environmentId, '--version', targetVersion)
  return invoke
}

function windowsActionScript(
  workDirectory: string,
  installer: string,
  environmentRoot: string,
  config: InstallerConfig,
  targetVersion: string,
  operationId: string,
  unit: string,
): string {
  const proxy = config.proxyUrl ?? ''
  return [
    `$ErrorActionPreference = 'Stop'`,
    `Get-ChildItem Env: | Where-Object { $_.Name -match '^(http|https|all|no)_proxy$' } | Remove-Item`,
    `$env:HTTP_PROXY = ${ps(proxy)}; $env:HTTPS_PROXY = ${ps(proxy)}; $env:NO_PROXY = ''; $env:KK_DAEMON_INSTALL_PROXY_ENV = '1'`,
    `$env:KK_STUDIO_UPDATE_OPERATION_ID = ${ps(operationId)}`,
    `$permitSeconds = 120`,
    `if ($env:KK_UPDATE_PERMIT_TIMEOUT_SECONDS -match '^[0-9]{1,4}$') { $permitSeconds = [int] $env:KK_UPDATE_PERMIT_TIMEOUT_SECONDS }`,
    `$failed = $true`,
    `try {`,
    `  [IO.File]::WriteAllText(${ps(join(workDirectory, 'ready'))}, '')`,
    `  $deadline = (Get-Date).AddSeconds($permitSeconds)`,
    `  while (-not (Test-Path -LiteralPath ${ps(join(workDirectory, 'permit'))})) { if ((Get-Date) -ge $deadline) { throw 'update receipt permit timed out' }; Start-Sleep -Seconds 1 }`,
    `  & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ${ps(installer)} update -EnvironmentId ${ps(config.environmentId)} -Version ${ps(targetVersion)}`,
    `  if ($LASTEXITCODE -ne 0) { throw 'installer failed; see local last-operation.json' }`,
    `  $failed = $false`,
    `} finally {`,
    `  if ($failed) {`,
    `    try {`,
    `      $current = Join-Path ${ps(environmentRoot)} 'last-operation.json'`,
    `      $recorded = $false`,
    `      if (Test-Path -LiteralPath $current -PathType Leaf) {`,
    `        try { $existing = Get-Content -LiteralPath $current -Raw | ConvertFrom-Json; if ($existing.operationId -eq ${ps(operationId)}) { $recorded = $true } } catch { }`,
    `      }`,
    `      if (-not $recorded -and (Test-Path -LiteralPath ${ps(environmentRoot)} -PathType Container)) {`,
    `        $json = '{"operationId":' + (${ps(JSON.stringify(operationId))}) + ',"type":"update","targetVersion":' + (${ps(JSON.stringify(targetVersion))}) + ',"phase":"job","result":"failed","time":' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ',"errorCode":"installer"}'`,
    `        $temporary = Join-Path ${ps(environmentRoot)} ('.last-operation.json.tmp.' + [Guid]::NewGuid().ToString('N'))`,
    `        [IO.File]::WriteAllText($temporary, $json + "\`n")`,
    `        Move-Item -LiteralPath $temporary -Destination $current -Force`,
    `      }`,
    `    } catch { }`,
    `  }`,
    `  Unregister-ScheduledTask -TaskName ${ps(unit)} -Confirm:$false -ErrorAction Continue`,
    `  Remove-Item -LiteralPath ${ps(workDirectory)} -Recurse -Force -ErrorAction Continue`,
    `}`,
  ].join('\n')
}

function windowsRegistrationScript(action: string, unit: string): string {
  return [
    `$ErrorActionPreference = 'Stop'`,
    `$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ${ps(`-NoProfile -NonInteractive -EncodedCommand ${encoded(action)}`)}`,
    `$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType Interactive -RunLevel Limited`,
    `Register-ScheduledTask -TaskName ${ps(unit)} -Action $action -Principal $principal | Out-Null`,
    `try { Start-ScheduledTask -TaskName ${ps(unit)} } catch { Unregister-ScheduledTask -TaskName ${ps(unit)} -Confirm:$false; throw }`,
  ].join('; ')
}

/**
 * 准备一个精确版本、官方来源的更新作业，并把受管 `update` 交接给服务作用域之外的 OS 作业。
 *
 * <p>作业只携带 environmentId 与目标版本：配置和凭据继续由安装器从卡片目录读取，在线更新永远不会
 * 写它们。代理决定来自当前已应用的卡片 `proxyUrl`；null 时作业环境显式直连。
 */
export async function prepareInstaller(
  command: Record<string, unknown>,
  config: InstallerConfig,
  signal: AbortSignal,
  deps: InstallerDependencies,
): Promise<UpdateOutcomeLike> {
  const { operationId, targetVersion } = command
  if (Object.keys(command).some(key => key !== 'operationId' && key !== 'targetVersion') ||
      !isCanonicalUuid(operationId) ||
      typeof targetVersion !== 'string' || !DAEMON_VERSION_PATTERN.test(targetVersion)) {
    throw new Error('invalid update command')
  }
  if (!isCanonicalUuid(config.environmentId)) {
    throw new Error('the daemon configuration has no canonical environment identity')
  }
  currentPlatformKey(deps.platform, deps.arch)
  requireManagedInstallation(config, deps.home, deps.platform)

  const environmentRoot = managedEnvironmentRoot(deps.home, config.environmentId)
  const directory = await mkdtemp(join(tmpdir(), 'kk-studio-update-'))
  await ensurePrivateDirectory(directory)
  let jobRegistered = false
  try {
    const installer = join(directory, deps.platform === 'win32' ? 'install.ps1' : 'install.sh')
    await downloadInstaller(installerUrl(deps.platform, targetVersion), installer, signal, deps.network)
    // The independent job needs the user manager's session, not the daemon's service scope.
    const serviceEnvironment = process.env
    const unit = `kk-studio-daemon-update-${operationId}`
    if (deps.platform === 'win32') {
      const action = windowsActionScript(directory, installer, environmentRoot, config, targetVersion, operationId, unit)
      const registration = windowsRegistrationScript(action, unit)
      jobRegistered = true
      await deps.launch('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded(registration)], AbortSignal.any([signal, AbortSignal.timeout(15_000)]))
    } else {
      await writePrivateFile(join(directory, 'run.sh'), UNIX_RUNNER)
      const invoke = unixInvocation(directory, installer, environmentRoot, config, targetVersion, operationId, deps.home, serviceEnvironment)
      const args = deps.platform === 'darwin'
        ? ['submit', '-l', unit, '--', ...invoke]
        : ['--user', '--collect', `--unit=${unit}`, ...invoke]
      jobRegistered = true
      await deps.launch(deps.platform === 'darwin' ? 'launchctl' : 'systemd-run', args, AbortSignal.any([signal, AbortSignal.timeout(15_000)]))
    }
    const started = AbortSignal.any([signal, AbortSignal.timeout(15_000)])
    for (;;) {
      started.throwIfAborted()
      try {
        await readFile(join(directory, 'ready'))
        break
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
      }
      await delay(100, undefined, { signal: started })
    }
    let released = false
    const lastOperation = join(environmentRoot, 'last-operation.json')
    return {
      phase: 'PREPARED',
      releasePermit: async () => {
        if (!released) {
          await readFile(join(directory, 'ready'))
          await writePrivateFile(join(directory, 'permit'), '')
          released = true
        }
      },
      watchFailure: (report) => {
        // 唯一持久结果固定为卡片目录的 last-operation.json；必须匹配本次 operationId，
        // 不能把上一次失败的记录当成本次结果。作业目录由 runner 自己清理，不保留历史。
        const timer = setInterval(() => {
          void readFile(lastOperation, 'utf8').then((text) => {
            let value: unknown
            try {
              value = JSON.parse(text)
            } catch {
              return
            }
            if (typeof value !== 'object' || value === null) {
              return
            }
            const record = value as { operationId?: unknown; result?: unknown }
            if (record.operationId !== operationId) {
              return
            }
            clearInterval(timer)
            if (record.result === 'failed') {
              report('official installer failed; check local last-operation.json')
            }
          }, () => {
            // 记录尚未出现或已被删除：继续等待本次操作的固定结果。
          })
        }, 1000)
        timer.unref()
        return () => clearInterval(timer)
      },
    }
  } catch (error) {
    if (!jobRegistered) {
      try {
        await rm(directory, { recursive: true, force: true })
      } catch (cleanupError) {
        throw new AggregateError([error, cleanupError], 'installer preparation and cleanup failed')
      }
    }
    throw error
  }
}

export async function handleUpdate(command: Record<string, unknown>, config: DaemonConfig, signal: AbortSignal): Promise<UpdateOutcomeLike> {
  try {
    return await prepareInstaller(command, toInstallerConfig(config), signal, {
      network: getOutboundNetwork(),
      platform: process.platform,
      arch: process.arch,
      home: homedir(),
      launch: launchInstallerJob,
    })
  } catch (error) {
    // Raw subprocess/network diagnostics can contain paths or credentials; never send them to Studio.
    const message = error instanceof AggregateError
      ? 'official installer preparation and cleanup failed; check local files'
      : 'official installer preparation failed; check daemon logs and network'
    process.stderr.write(`daemon update: ${message}\n`)
    return { phase: 'FAILED', message }
  }
}
