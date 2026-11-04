import { spawn, type ChildProcess } from 'node:child_process'
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'

import { afterEach, describe, expect, it, vi } from 'vitest'

import type { DaemonConfig } from '../src/contracts.js'
import { handleUpdate, launchInstallerJob, prepareInstaller, type InstallerDependencies } from '../src/installer.js'
import { applyNetworkSettings } from '../src/network.js'

const operationId = '7c9e6679-7425-40de-944b-e07fc1f90ae7'
const command = { operationId, targetVersion: '2.0.0' }
const homes: string[] = []

async function waitForFile(file: string): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      await stat(file)
      return
    } catch {
      await new Promise(resolve => setTimeout(resolve, 10))
    }
  }
  throw new Error('fixture job did not start')
}

async function fixture(): Promise<{ home: string; config: DaemonConfig }> {
  const home = await mkdtemp(join(tmpdir(), 'daemon installer '))
  homes.push(home)
  const root = join(home, '.kk-studio')
  await mkdir(root, { mode: 0o700 })
  const config = {
    configPath: join(root, 'daemon.json'),
    tokenPath: join(root, 'daemon.token'),
    dataDir: root,
    distributionDir: join(root, 'lib'),
    studioUrl: 'https://studio.example',
    gatewayUri: 'wss://studio.example',
    bashExecutable: '/bin/bash',
    note: null,
    lsp: {},
  }
  await writeFile(config.configPath, '{}')
  await writeFile(config.tokenPath, 'private-token')
  return { home, config }
}

function deps(home: string, script: string): InstallerDependencies {
  return {
    home,
    platform: 'linux',
    arch: 'x64',
    network: {
      async withResponse(url, init, _signal, use) {
        expect(url).toBe('https://raw.githubusercontent.com/fengwk/kk-studio/v2.0.0/scripts/daemon/install.sh')
        expect(init.redirect).toBe('error')
        return use({ statusCode: 200, statusMessage: 'OK', headers: {}, body: Readable.from([script]), async dispose() {} })
      },
    },
    async launch() {},
  }
}

afterEach(async () => {
  applyNetworkSettings(null, '')
  vi.unstubAllEnvs()
  await Promise.all(homes.splice(0).map(home => rm(home, { recursive: true, force: true })))
})

describe('official installer handoff', () => {
  it('holds the independent job until the receipt permit, then invokes the pinned installer with existing inputs', async () => {
    // Execute the real generated runner, not an imitation of its wait/argument logic.
    const { home, config } = await fixture()
    applyNetworkSettings('http://localhost:8080', 'localhost,.internal')
    vi.stubEnv('HtTp_PrOxY', 'http://host-proxy.invalid:3128')
    const marker = join(home, 'installer-args')
    const environment = join(home, 'installer-env')
    const adapter = deps(home, `printf '%s\\n' "$@" > '${marker}'\nenv > '${environment}'\n`)
    let child: ChildProcess | undefined
    adapter.launch = async (file, args) => {
      expect(file).toBe('systemd-run')
      const envIndex = args.indexOf('env')
      expect(args).toContain('https_proxy=http://localhost:8080')
      expect(args).toContain('NO_PROXY=localhost,.internal')
      expect(args).toContain(`HOME=${home}`)
      child = spawn(args[envIndex]!, args.slice(envIndex + 1), { stdio: 'ignore' })
      await waitForFile(join(args[args.indexOf('bash') + 2]!, '..', 'ready'))
    }
    const prepared = await prepareInstaller(command, config, new AbortController().signal, adapter)
    expect(prepared.phase).toBe('PREPARED')
    expect(child).toBeDefined()
    await expect(stat(marker)).rejects.toMatchObject({ code: 'ENOENT' })
    const exited = new Promise<number | null>((resolve, reject) => {
      child!.once('error', reject)
      child!.once('exit', resolve)
    })
    await prepared.releasePermit!()
    await prepared.releasePermit!()
    expect(await exited).toBe(0)
    expect((await readFile(marker, 'utf8')).trim().split('\n')).toEqual([
      'install', '--config-file', config.configPath, '--token-file', config.tokenPath, '--version', '2.0.0',
    ])
    const inherited = await readFile(environment, 'utf8')
    expect(inherited).toContain('HTTPS_PROXY=http://localhost:8080\n')
    expect(inherited).toContain('NO_PROXY=localhost,.internal\n')
    expect(inherited).not.toContain('host-proxy.invalid')
    expect(await readFile(config.tokenPath, 'utf8')).toBe('private-token')
  })

  it('rejects untrusted targets and unmanaged layouts before requesting the installer', async () => {
    // Neither command-provided paths nor arbitrary installations may select executable content.
    const { home, config } = await fixture()
    const adapter = deps(home, '')
    await expect(prepareInstaller({ ...command, targetVersion: '../other' }, config, AbortSignal.timeout(1000), adapter))
      .rejects.toThrow('invalid update command')
    await expect(prepareInstaller({ ...command, artifactUrl: 'https://example.invalid' }, config, AbortSignal.timeout(1000), adapter))
      .rejects.toThrow('invalid update command')
    await expect(prepareInstaller(command, { ...config, distributionDir: home }, AbortSignal.timeout(1000), adapter))
      .rejects.toThrow('official managed installation')
  })

  it('fails closed on an oversized or failed download without launching a job', async () => {
    const { home, config } = await fixture()
    const adapter = deps(home, 'x'.repeat(1024 * 1024 + 1))
    let launches = 0
    adapter.launch = async () => { launches++ }
    await expect(prepareInstaller(command, config, AbortSignal.timeout(5000), adapter))
      .rejects.toThrow('exceeds size limit')
    expect(launches).toBe(0)
  })

  it('rejects empty and non-success installer responses', async () => {
    // Failed HTTP and truncated successful responses must never become executable jobs.
    const { home, config } = await fixture()
    const adapter = deps(home, '')
    await expect(prepareInstaller(command, config, AbortSignal.timeout(5000), adapter))
      .rejects.toThrow('installer is empty')
    adapter.network.withResponse = async (_url, _init, _signal, use) =>
      use({ statusCode: 404, statusMessage: 'Not Found', headers: {}, body: Readable.from([]), async dispose() {} })
    await expect(prepareInstaller(command, config, AbortSignal.timeout(5000), adapter))
      .rejects.toThrow('download failed')
  })

  it('returns only a bounded safe failure from the production adapter', async () => {
    // Arbitrary command input must not be reflected in the protocol or diagnostics.
    const { config } = await fixture()
    const output = vi.spyOn(process.stderr, 'write').mockImplementation(() => true)
    try {
      const outcome = await handleUpdate({ ...command, targetVersion: 'secret invalid' }, config, AbortSignal.timeout(1000))
      expect(outcome).toEqual({ phase: 'FAILED', message: 'official installer preparation failed; check daemon logs and network' })
      expect(output).toHaveBeenCalledWith('daemon update: official installer preparation failed; check daemon logs and network\n')
    } finally {
      output.mockRestore()
    }
  })

  it('observes service-manager process success and failure', async () => {
    // A rejected manager command cannot be reported as a successfully prepared handoff.
    await expect(launchInstallerJob(process.execPath, ['-e', 'process.exit(0)'], AbortSignal.timeout(5000)))
      .resolves.toBeUndefined()
    await expect(launchInstallerJob(process.execPath, ['-e', 'process.exit(3)'], AbortSignal.timeout(5000)))
      .rejects.toThrow()
  })

  it('reports installer failure from the real detached runner', async () => {
    // The job writes a failure marker after a non-zero installer exit, without leaking its output.
    const { home, config } = await fixture()
    const adapter = deps(home, 'echo "sensitive detail" >&2\nexit 7\n')
    let child: ChildProcess | undefined
    adapter.launch = async (_file, args) => {
      const envIndex = args.indexOf('env')
      child = spawn(args[envIndex]!, args.slice(envIndex + 1), { stdio: 'ignore' })
      await waitForFile(join(args[args.indexOf('bash') + 2]!, '..', 'ready'))
    }
    const prepared = await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)
    const exit = new Promise<number | null>(resolve => child!.once('exit', resolve))
    await prepared.releasePermit!()
    expect(await exit).toBe(7)
    const reported = new Promise<string>(resolve => prepared.watchFailure!(resolve))
    expect(await reported).toBe('official installer failed; check local install.log')
  })

  it('reports a job that never starts instead of holding admission indefinitely', async () => {
    const { home, config } = await fixture()
    await expect(prepareInstaller(command, config, AbortSignal.timeout(100), deps(home, 'installer'))).rejects.toThrow()
  })

  it('submits a detached macOS job without exposing token contents', async () => {
    const { home, config } = await fixture()
    const adapter = deps(home, 'official installer')
    adapter.platform = 'darwin'
    adapter.launch = async (file, args) => {
      expect(file).toBe('launchctl')
      expect(args).toContain('submit')
      expect(args.join(' ')).not.toContain('private-token')
      expect(args).toContain(config.tokenPath)
      await writeFile(join(args[args.indexOf('bash') + 2]!, '..', 'ready'), '')
    }
    expect((await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)).phase).toBe('PREPARED')
  })

  it('uses an encoded, non-repeating Windows scheduled task with the target version', async () => {
    // Decode both task-registration layers to verify argv and avoid shell-injection assumptions.
    const { home, config } = await fixture()
    applyNetworkSettings('http://localhost:8080', 'localhost,.internal')
    const adapter = deps(home, 'official installer')
    adapter.platform = 'win32'
    adapter.network.withResponse = async (_url, _init, _signal, use) =>
      use({ statusCode: 200, statusMessage: 'OK', headers: {}, body: Readable.from(['installer']), async dispose() {} })
    let registration = ''
    adapter.launch = async (file, args) => {
      expect(file).toBe('powershell.exe')
      registration = Buffer.from(args.at(-1)!, 'base64').toString('utf16le')
      const action = /-EncodedCommand ([A-Za-z0-9+/=]+)/.exec(registration)
      const task = Buffer.from(action![1]!, 'base64').toString('utf16le')
      const ready = /\[IO\.File\]::WriteAllText\('([^']+[/\\]ready)'/.exec(task)
      await writeFile(ready![1]!, '')
    }
    const result = await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)
    const action = /-EncodedCommand ([A-Za-z0-9+/=]+)/.exec(registration)
    expect(action).not.toBeNull()
    const task = Buffer.from(action![1]!, 'base64').toString('utf16le')
    expect(task).toContain(`-Version '2.0.0'`)
    expect(task).toContain(`-TokenFile '${config.tokenPath}'`)
    expect(task).toContain("$env:HTTPS_PROXY = 'http://localhost:8080'")
    expect(task).toContain("$env:NO_PROXY = 'localhost,.internal'")
    expect(task).not.toContain('private-token')
    expect(task).toContain('Unregister-ScheduledTask')
    expect(registration).not.toContain('New-ScheduledTaskTrigger')
    await result.releasePermit!()
  })

  it.each(['linux', 'win32'] as const)('explicitly clears inherited proxy for a direct %s job', async (platform) => {
    // The service manager may have a different environment from the daemon.
    vi.stubEnv('HTTPS_PROXY', 'http://host-proxy.invalid:3128')
    const { home, config } = await fixture()
    const adapter = deps(home, 'installer')
    adapter.platform = platform
    if (platform === 'win32') {
      adapter.network.withResponse = async (_url, _init, _signal, use) =>
        use({ statusCode: 200, statusMessage: 'OK', headers: {}, body: Readable.from(['installer']), async dispose() {} })
    }
    adapter.launch = async (_file, args) => {
      if (platform === 'linux') {
        expect(args).toContain('-i')
        expect(args).toContain('https_proxy=')
        expect(args).toContain('NO_PROXY=')
        expect(args.join(' ')).not.toContain('host-proxy.invalid')
        await writeFile(join(args[args.indexOf('bash') + 2]!, '..', 'ready'), '')
      } else {
        const registration = Buffer.from(args.at(-1)!, 'base64').toString('utf16le')
        const action = /-EncodedCommand ([A-Za-z0-9+/=]+)/.exec(registration)
        const task = Buffer.from(action![1]!, 'base64').toString('utf16le')
        expect(task).toContain('Remove-Item')
        expect(task).toContain("$env:HTTPS_PROXY = ''")
        expect(task).toContain("$env:NO_PROXY = ''")
        expect(task).not.toContain('host-proxy.invalid')
        const ready = /\[IO\.File\]::WriteAllText\('([^']+[/\\]ready)'/.exec(task)
        await writeFile(ready![1]!, '')
      }
    }
    expect((await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)).phase).toBe('PREPARED')
  })
})
