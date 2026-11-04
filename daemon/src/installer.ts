import { execFile } from 'node:child_process'
import { accessSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'

import type { DaemonConfig } from './contracts.js'
import { getOutboundNetwork, type OutboundNetwork } from './network.js'
import { ensurePrivateDirectory, writePrivateFile } from './native/index.js'
import { currentPlatformKey } from './release.js'
import type { UpdateOutcomeLike } from './runtime-ports.js'

const exec = promisify(execFile)
const MAX_INSTALLER_BYTES = 1024 * 1024
const VERSION = /^[0-9A-Za-z._+-]{1,64}$/
const OPERATION = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

// The independent job only waits for the receipt and invokes the official installer.
// It never downloads or replaces the daemon itself.
const UNIX_RUNNER = `#!/bin/bash
set -eu
permit=$1
log=$2
shift 2
exec >>"$log" 2>&1
trap 'code=$?; if [ "$code" -ne 0 ]; then : > "\${permit%/*}/failed"; fi' EXIT
: > "\${permit%/*}/ready"
i=0
until [ -f "$permit" ]; do
  if [ "$i" -ge 120 ]; then echo "update receipt permit timed out"; exit 1; fi
  sleep 1
  i=$((i + 1))
done
bash "$@"
`

function ps(value: string): string {
  return `'${value.replaceAll("'", "''")}'`
}

function encoded(script: string): string {
  return Buffer.from(script, 'utf16le').toString('base64')
}

export interface InstallerDependencies {
  network: Pick<OutboundNetwork, 'withResponse' | 'selectProxy'>
  platform: NodeJS.Platform
  arch: string
  home: string
  launch(file: string, args: string[], signal: AbortSignal): Promise<void>
}

/** The service-manager client is short-lived; the installed OS job outlives it. */
export async function launchInstallerJob(file: string, args: string[], signal: AbortSignal): Promise<void> {
  await exec(file, args, { signal, timeout: 15_000, maxBuffer: 64 * 1024, windowsHide: true })
}

/** Prepare an official, version-pinned installer and start a job outside the daemon's service scope. */
export async function prepareInstaller(
  command: Record<string, unknown>,
  config: DaemonConfig,
  signal: AbortSignal,
  deps: InstallerDependencies,
): Promise<UpdateOutcomeLike> {
  const { operationId, targetVersion } = command
  if (Object.keys(command).some(key => key !== 'operationId' && key !== 'targetVersion') ||
      typeof operationId !== 'string' || !OPERATION.test(operationId) ||
      typeof targetVersion !== 'string' || !VERSION.test(targetVersion)) {
    throw new Error('invalid update command')
  }
  currentPlatformKey(deps.platform, deps.arch)
  const root = join(deps.home, '.kk-studio')
  const equalPath = (a: string, b: string): boolean => deps.platform === 'win32'
    ? resolve(a).toLowerCase() === resolve(b).toLowerCase()
    : resolve(a) === resolve(b)
  if (!equalPath(config.configPath, join(root, 'daemon.json')) ||
      !equalPath(config.tokenPath, join(root, 'daemon.token')) ||
      !equalPath(config.distributionDir, join(root, 'lib'))) {
    throw new Error('online update requires the official managed installation')
  }

  const updates = join(root, 'updates')
  await ensurePrivateDirectory(updates)
  const directory = await mkdtemp(join(updates, `${operationId}-`))
  await ensurePrivateDirectory(directory)
  let jobRegistered = false
  try {
  const installer = join(directory, deps.platform === 'win32' ? 'install.ps1' : 'install.sh')
  const permit = join(directory, 'permit')
  const log = join(directory, 'install.log')
  await writePrivateFile(log, '')
  const url = `https://raw.githubusercontent.com/fengwk/kk-studio/v${targetVersion}/scripts/daemon/${deps.platform === 'win32' ? 'install.ps1' : 'install.sh'}`
  const timeout = AbortSignal.any([signal, AbortSignal.timeout(60_000)])
  await deps.network.withResponse(url, { method: 'GET', redirect: 'error' }, timeout, async response => {
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
    await writePrivateFile(installer, Buffer.concat(chunks))
  })
  // The current network policy already resolves environment/system proxy and bypass rules.
  // The proxy slice will replace this with its platform-provided subprocess environment.
  const choice = await deps.network.selectProxy('https://github.com/', timeout)
  const proxy = choice.kind === 'proxy' ? choice.url : ''
  // The independent job needs the user manager's session, not the daemon's service scope.
  const serviceEnvironment = process.env
  const unit = `kk-studio-daemon-update-${operationId}`
  if (deps.platform === 'win32') {
    const actionScript = [
      `$ErrorActionPreference = 'Stop'`,
      `$env:https_proxy = ${ps(proxy)}; $env:HTTPS_PROXY = ${ps(proxy)}; $env:KK_DAEMON_INSTALL_PROXY_ENV = '1'`,
      `try {`,
      `  [IO.File]::WriteAllText(${ps(join(directory, 'ready'))}, '')`,
      `  $deadline = (Get-Date).AddSeconds(120)`,
      `  while (-not (Test-Path -LiteralPath ${ps(permit)})) { if ((Get-Date) -ge $deadline) { throw 'update receipt permit timed out' }; Start-Sleep -Seconds 1 }`,
      `  & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ${ps(installer)} install -ConfigFile ${ps(config.configPath)} -TokenFile ${ps(config.tokenPath)} -Version ${ps(targetVersion)} *>> ${ps(log)}`,
      `  if ($LASTEXITCODE -ne 0) { throw 'installer failed; see install.log' }`,
      `} catch { $_.Exception.Message | Out-File -LiteralPath ${ps(log)} -Append; [IO.File]::WriteAllText(${ps(join(directory, 'failed'))}, ''); throw }`,
      `finally { Unregister-ScheduledTask -TaskName ${ps(unit)} -Confirm:$false -ErrorAction Continue }`,
    ].join('\n')
    const action = `-NoProfile -NonInteractive -EncodedCommand ${encoded(actionScript)}`
    const register = [
      `$ErrorActionPreference = 'Stop'`,
      `$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ${ps(action)}`,
      `$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType Interactive -RunLevel Limited`,
      `Register-ScheduledTask -TaskName ${ps(unit)} -Action $action -Principal $principal | Out-Null`,
      `try { Start-ScheduledTask -TaskName ${ps(unit)} } catch { Unregister-ScheduledTask -TaskName ${ps(unit)} -Confirm:$false; throw }`,
    ].join('; ')
    jobRegistered = true
    await deps.launch('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded(register)], timeout)
  } else {
    const runner = join(directory, 'run.sh')
    await writePrivateFile(runner, UNIX_RUNNER)
    const invoke = ['env', `HOME=${deps.home}`, `https_proxy=${proxy}`, `HTTPS_PROXY=${proxy}`,
      `http_proxy=${proxy}`, `HTTP_PROXY=${proxy}`, 'all_proxy=', 'ALL_PROXY=', 'no_proxy=', 'NO_PROXY=',
      ...(serviceEnvironment.XDG_RUNTIME_DIR ? [`XDG_RUNTIME_DIR=${serviceEnvironment.XDG_RUNTIME_DIR}`] : []),
      ...(serviceEnvironment.DBUS_SESSION_BUS_ADDRESS ? [`DBUS_SESSION_BUS_ADDRESS=${serviceEnvironment.DBUS_SESSION_BUS_ADDRESS}`] : []),
      'bash', runner, permit, log,
      installer, 'install', '--config-file', config.configPath, '--token-file', config.tokenPath, '--version', targetVersion]
    const args = deps.platform === 'darwin'
      ? ['submit', '-l', unit, '--', ...invoke]
      : ['--user', '--collect', `--unit=${unit}`, ...invoke]
    jobRegistered = true
    await deps.launch(deps.platform === 'darwin' ? 'launchctl' : 'systemd-run', args, timeout)
  }
  const started = AbortSignal.any([signal, AbortSignal.timeout(15_000)])
  for (;;) {
    started.throwIfAborted()
    try {
      accessSync(join(directory, 'ready'))
      break
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    }
    await delay(100, undefined, { signal: started })
  }
  let released = false
  return {
    phase: 'PREPARED',
    releasePermit: async () => {
      if (!released) {
        accessSync(join(directory, 'ready'))
        await writePrivateFile(permit, '')
        released = true
      }
    },
    watchFailure: (report) => {
      // If the installer fails before stopping this daemon, free the admission and
      // report a fixed error. After shutdown the OS job's private log remains available.
      const timer = setInterval(() => {
        try {
          accessSync(join(directory, 'failed'))
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
            clearInterval(timer)
            report('cannot inspect official installer outcome; check local logs')
            return
          }
          return
        }
        clearInterval(timer)
        report('official installer failed; check local install.log')
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
    return await prepareInstaller(command, config, signal, {
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
