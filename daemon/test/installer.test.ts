import { spawn, type ChildProcess } from 'node:child_process'
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { setTimeout as sleep } from 'node:timers/promises'

import { afterEach, describe, expect, it, vi } from 'vitest'

import type { DaemonConfig } from '../src/contracts.js'
import {
  handleUpdate,
  launchInstallerJob,
  prepareInstaller,
  toInstallerConfig,
  type InstallerConfig,
  type InstallerDependencies,
} from '../src/installer.js'
import { applyNetworkSettings } from '../src/network.js'

const operationId = '7c9e6679-7425-40de-944b-e07fc1f90ae7'
const environmentId = '11111111-1111-4111-8111-111111111111'
const command = { operationId, targetVersion: '2.0.0' }
const homes: string[] = []

async function waitForFile(file: string): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      await stat(file)
      return
    } catch {
      await sleep(10)
    }
  }
  throw new Error('fixture job did not start')
}

async function fixture(proxyUrl: string | null = null): Promise<{
  home: string
  root: string
  config: InstallerConfig
}> {
  const home = await mkdtemp(join(tmpdir(), 'daemon installer '))
  homes.push(home)
  const root = join(home, '.kk-studio', 'environments', environmentId)
  await mkdir(root, { recursive: true, mode: 0o700 })
  const config: InstallerConfig = {
    environmentId,
    proxyUrl,
    configPath: join(root, 'daemon.json'),
    tokenPath: join(root, 'daemon.token'),
    distributionDir: join(root, 'lib'),
  }
  await writeFile(config.configPath, '{"environmentId":"' + environmentId + '"}')
  await writeFile(config.tokenPath, 'private-token')
  return { home, root, config }
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

/** Decode the Windows registration and its inner scheduled action. */
function decodeWindows(args: string[]): { registration: string; action: string } {
  const registration = Buffer.from(args.at(-1)!, 'base64').toString('utf16le')
  const encodedAction = /-EncodedCommand ([A-Za-z0-9+/=]+)/.exec(registration)
  expect(encodedAction).not.toBeNull()
  return { registration, action: Buffer.from(encodedAction![1]!, 'base64').toString('utf16le') }
}

afterEach(async () => {
  applyNetworkSettings(null, '')
  vi.unstubAllEnvs()
  await Promise.all(homes.splice(0).map(home => rm(home, { recursive: true, force: true })))
})

describe('official installer handoff', () => {
  it.skipIf(process.platform === 'win32')('hands update only the environment identity and version to the real runner', async () => {
    // Execute the real generated runner, not an imitation of its wait/argument/cleanup logic.
    const { home, root, config } = await fixture()
    applyNetworkSettings('http://localhost:8080', 'localhost,.internal')
    vi.stubEnv('HtTp_PrOxY', 'http://host-proxy.invalid:3128')
    const marker = join(home, 'installer-args')
    const environment = join(home, 'installer-env')
    const adapter = deps(home, `printf '%s\\n' "$@" > '${marker}'\nenv > '${environment}'\n`)
    let child: ChildProcess | undefined
    let workDirectory = ''
    adapter.launch = async (file, args) => {
      expect(file).toBe('systemd-run')
      const envIndex = args.indexOf('env')
      expect(args).toContain('https_proxy=')
      expect(args).toContain('HTTPS_PROXY=')
      expect(args).toContain('NO_PROXY=')
      expect(args).toContain(`HOME=${home}`)
      expect(args).toContain(`KK_STUDIO_UPDATE_OPERATION_ID=${operationId}`)
      expect(args.join(' ')).not.toContain('host-proxy.invalid')
      const runnerIndex = args.indexOf('bash') + 1
      workDirectory = args[runnerIndex + 1]!
      expect(workDirectory.startsWith(tmpdir())).toBe(true)
      child = spawn(args[envIndex]!, args.slice(envIndex + 1), { stdio: 'ignore' })
      await waitForFile(join(workDirectory, 'ready'))
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
      'update', '--environment-id', environmentId, '--version', '2.0.0',
    ])
    const inherited = await readFile(environment, 'utf8')
    expect(inherited).toContain('https_proxy=\n')
    expect(inherited).toContain('HTTP_PROXY=\n')
    expect(inherited).not.toContain('host-proxy.invalid')
    // 独立作业目录由 runner 自己清理；卡片目录只保留配置、凭据和固定诊断记录。
    await expect(stat(workDirectory)).rejects.toMatchObject({ code: 'ENOENT' })
    await expect(stat(join(root, 'updates'))).rejects.toMatchObject({ code: 'ENOENT' })
    expect(await readFile(config.tokenPath, 'utf8')).toBe('private-token')
  })

  it('uses the applied card proxy for the independent job', async () => {
    // 卡片代理是本次更新下载的唯一代理决定；宿主服务环境不得改写它。
    vi.stubEnv('HTTPS_PROXY', 'http://host-proxy.invalid:3128')
    const { home, config } = await fixture('http://card-proxy.invalid:8080')
    const adapter = deps(home, 'installer')
    let args: string[] = []
    adapter.launch = async (_file, received) => {
      args = received
      const runner = args[args.indexOf('bash') + 1]!
      await writeFile(join(join(runner, '..'), 'ready'), '')
    }
    expect((await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)).phase).toBe('PREPARED')
    expect(args).toContain('https_proxy=http://card-proxy.invalid:8080')
    expect(args).toContain('HTTPS_PROXY=http://card-proxy.invalid:8080')
    expect(args.join(' ')).not.toContain('host-proxy.invalid')
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
    await expect(prepareInstaller(command, { ...config, environmentId: '7c9e6679-7425-40de-944b-e07fc1f90ae7'.toUpperCase() }, AbortSignal.timeout(1000), adapter))
      .rejects.toThrow('canonical environment identity')
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
      const daemonConfig = {
        environmentId: config.environmentId,
        configVersion: 1n,
        proxyUrl: null,
        configPath: config.configPath,
        tokenPath: config.tokenPath,
        distributionDir: config.distributionDir,
        dataDir: join(config.distributionDir, '..'),
        studioUrl: 'https://studio.example',
        gatewayUri: 'wss://studio.example',
        bashExecutable: '/bin/bash',
        lsp: {},
      } as unknown as DaemonConfig
      const outcome = await handleUpdate({ ...command, targetVersion: 'secret invalid' }, daemonConfig, AbortSignal.timeout(1000))
      expect(outcome).toEqual({ phase: 'FAILED', message: 'official installer preparation failed; check daemon logs and network' })
      expect(output).toHaveBeenCalledWith('daemon update: official installer preparation failed; check daemon logs and network\n')
    } finally {
      output.mockRestore()
    }
  })

  it('rejects a parsed configuration without the frozen installation identity', () => {
    // 缺少 environmentId/proxyUrl 时绝不允许把更新交给任意目录。
    expect(() => toInstallerConfig({
      configPath: '/tmp/daemon.json',
      tokenPath: '/tmp/daemon.token',
      distributionDir: '/tmp/lib',
    } as unknown as DaemonConfig)).toThrow('canonical environment identity')
  })

  it('observes service-manager process success and failure', async () => {
    // A rejected manager command cannot be reported as a successfully prepared handoff.
    await expect(launchInstallerJob(process.execPath, ['-e', 'process.exit(0)'], AbortSignal.timeout(5000)))
      .resolves.toBeUndefined()
    await expect(launchInstallerJob(process.execPath, ['-e', 'process.exit(3)'], AbortSignal.timeout(5000)))
      .rejects.toThrow()
  })

  it.skipIf(process.platform === 'win32')('cleans its job directory when the installer fails', async () => {
    // The runner owns cleanup: no install.log, archive, runner, ready/permit or history directory remains.
    const { home, config } = await fixture()
    const adapter = deps(home, 'echo "sensitive detail" >&2\nexit 7\n')
    let child: ChildProcess | undefined
    let workDirectory = ''
    adapter.launch = async (_file, args) => {
      const envIndex = args.indexOf('env')
      workDirectory = args[args.indexOf('bash') + 2]!
      child = spawn(args[envIndex]!, args.slice(envIndex + 1), { stdio: 'ignore' })
      await waitForFile(join(workDirectory, 'ready'))
    }
    const prepared = await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)
    const exit = new Promise<number | null>(resolve => child!.once('exit', resolve))
    await prepared.releasePermit!()
    expect(await exit).toBe(7)
    for (const leftover of ['run.sh', 'install.sh', 'ready', 'permit']) {
      await expect(stat(join(workDirectory, leftover))).rejects.toMatchObject({ code: 'ENOENT' })
    }
    await expect(stat(workDirectory)).rejects.toMatchObject({ code: 'ENOENT' })
  })

  it('reports a failure only for the matching operation in last-operation.json', async () => {
    // 固定结果文件必须匹配 operationId：上一次失败不能被当成本次结果。
    const { home, root, config } = await fixture()
    const adapter = deps(home, 'installer')
    adapter.launch = async (_file, args) => {
      const runner = args[args.indexOf('bash') + 1]!
      await writeFile(join(runner, '..', 'ready'), '')
    }
    const prepared = await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)
    const lastOperation = join(root, 'last-operation.json')
    const reports: string[] = []
    const stop = prepared.watchFailure!(message => reports.push(message))
    try {
      await writeFile(lastOperation, JSON.stringify({
        operationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        type: 'update', targetVersion: '1.0.0', phase: 'publish', result: 'failed', time: 1, errorCode: 'publish',
      }))
      await sleep(1_200)
      expect(reports).toEqual([])
      await writeFile(lastOperation, JSON.stringify({
        operationId, type: 'update', targetVersion: '2.0.0', phase: 'publish', result: 'failed', time: 2, errorCode: 'publish',
      }))
      for (let attempt = 0; attempt < 30 && reports.length === 0; attempt++) {
        await sleep(100)
      }
      expect(reports).toEqual(['official installer failed; check local last-operation.json'])
    } finally {
      stop()
    }
  })

  it.skipIf(process.platform === 'win32')('records a bounded failure when the installer fails before writing any result', async () => {
    // 下载/取得锁之前失败时安装器不写结果；runner 必须为本次 operationId 留下受限失败记录，
    // 否则旧 Daemon 的 watchFailure 永远看不到结果。
    const { home, root, config } = await fixture()
    const adapter = deps(home, 'exit 7\n')
    let child: ChildProcess | undefined
    let workDirectory = ''
    adapter.launch = async (_file, args) => {
      const envIndex = args.indexOf('env')
      workDirectory = args[args.indexOf('bash') + 2]!
      child = spawn(args[envIndex]!, args.slice(envIndex + 1), { stdio: 'ignore' })
      await waitForFile(join(workDirectory, 'ready'))
    }
    const prepared = await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)
    const reports: string[] = []
    const stop = prepared.watchFailure!(message => reports.push(message))
    try {
      const exit = new Promise<number | null>(resolve => child!.once('exit', resolve))
      await prepared.releasePermit!()
      expect(await exit).toBe(7)
      const record = JSON.parse(await readFile(join(root, 'last-operation.json'), 'utf8'))
      expect(record).toMatchObject({
        operationId, type: 'update', targetVersion: '2.0.0', phase: 'job', result: 'failed', errorCode: 'installer',
      })
      expect(Object.keys(record).sort()).toEqual(['errorCode', 'operationId', 'phase', 'result', 'targetVersion', 'time', 'type'])
      for (let attempt = 0; attempt < 30 && reports.length === 0; attempt++) {
        await sleep(100)
      }
      expect(reports).toEqual(['official installer failed; check local last-operation.json'])
      await expect(stat(workDirectory)).rejects.toMatchObject({ code: 'ENOENT' })
    } finally {
      stop()
    }
  })

  it.skipIf(process.platform === 'win32')('records the failure when the receipt permit times out', async () => {
    // 回执许可超时同样发生在安装器取得锁之前；作业必须放弃准入并留下本次失败结果。
    vi.stubEnv('KK_UPDATE_PERMIT_TIMEOUT_SECONDS', '1')
    const { home, root, config } = await fixture()
    const adapter = deps(home, 'installer')
    let child: ChildProcess | undefined
    let workDirectory = ''
    adapter.launch = async (_file, args) => {
      const envIndex = args.indexOf('env')
      workDirectory = args[args.indexOf('bash') + 2]!
      child = spawn(args[envIndex]!, args.slice(envIndex + 1), { stdio: 'ignore' })
      await waitForFile(join(workDirectory, 'ready'))
    }
    const prepared = await prepareInstaller(command, config, AbortSignal.timeout(10_000), adapter)
    expect(prepared.phase).toBe('PREPARED')
    const exit = new Promise<number | null>(resolve => child!.once('exit', resolve))
    expect(await exit).toBe(1)
    const record = JSON.parse(await readFile(join(root, 'last-operation.json'), 'utf8'))
    expect(record).toMatchObject({ operationId, result: 'failed', errorCode: 'installer' })
    await expect(stat(workDirectory)).rejects.toMatchObject({ code: 'ENOENT' })
  })

  it.skipIf(process.platform === 'win32')('never overwrites a matching installer result with the runner fallback', async () => {
    // 安装器已写入匹配本次 operationId 的结果时，runner 的兜底记录必须让位于真实阶段。
    const { home, root, config } = await fixture()
    const adapter = deps(home, [
      `printf '%s\\n' '{"operationId":"${operationId}","type":"update","targetVersion":"2.0.0","phase":"lock","result":"failed","time":1,"errorCode":"lock"}' > '${join(root, 'last-operation.json')}'`,
      'exit 7',
      '',
    ].join('\n'))
    let child: ChildProcess | undefined
    adapter.launch = async (_file, args) => {
      const envIndex = args.indexOf('env')
      const work = args[args.indexOf('bash') + 2]!
      child = spawn(args[envIndex]!, args.slice(envIndex + 1), { stdio: 'ignore' })
      await waitForFile(join(work, 'ready'))
    }
    const prepared = await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)
    const exit = new Promise<number | null>(resolve => child!.once('exit', resolve))
    await prepared.releasePermit!()
    expect(await exit).toBe(7)
    const record = JSON.parse(await readFile(join(root, 'last-operation.json'), 'utf8'))
    expect(record.phase).toBe('lock')
  })

  it('reports a job that never starts instead of holding admission indefinitely', async () => {
    const { home, config } = await fixture()
    await expect(prepareInstaller(command, config, AbortSignal.timeout(100), deps(home, 'installer'))).rejects.toThrow()
  })

  it('submits a detached macOS job with the same environment/version contract', async () => {
    const { home, config } = await fixture()
    const adapter = deps(home, 'official installer')
    adapter.platform = 'darwin'
    adapter.launch = async (file, args) => {
      expect(file).toBe('launchctl')
      expect(args).toContain('submit')
      expect(args.join(' ')).not.toContain('private-token')
      expect(args).toContain(environmentId)
      expect(args).toContain('2.0.0')
      await writeFile(join(args[args.indexOf('bash') + 1]!, '..', 'ready'), '')
    }
    expect((await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)).phase).toBe('PREPARED')
  })

  it('uses an encoded, non-repeating Windows scheduled task with the update contract', async () => {
    // Decode both task-registration layers to verify argv and avoid shell-injection assumptions.
    const { home, config } = await fixture('http://card-proxy.invalid:8080')
    applyNetworkSettings('http://localhost:8080', 'localhost,.internal')
    const adapter = deps(home, 'official installer')
    adapter.platform = 'win32'
    adapter.network.withResponse = async (_url, _init, _signal, use) =>
      use({ statusCode: 200, statusMessage: 'OK', headers: {}, body: Readable.from(['installer']), async dispose() {} })
    let registration = ''
    let action = ''
    adapter.launch = async (file, args) => {
      expect(file).toBe('powershell.exe')
      const decoded = decodeWindows(args)
      registration = decoded.registration
      action = decoded.action
      const ready = /\[IO\.File\]::WriteAllText\('([^']+[/\\]ready)'/.exec(action)
      await writeFile(ready![1]!, '')
    }
    const result = await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)
    expect(action).toContain(`update -EnvironmentId '${environmentId}' -Version '2.0.0'`)
    expect(action).not.toContain('private-token')
    expect(action).toContain("$env:HTTPS_PROXY = 'http://card-proxy.invalid:8080'")
    expect(action).toContain("$env:NO_PROXY = ''")
    expect(action).toContain(`$env:KK_STUDIO_UPDATE_OPERATION_ID = '${operationId}'`)
    expect(action).toContain('Unregister-ScheduledTask')
    expect(action).toContain('Remove-Item -LiteralPath')
    // 子安装器在取得锁前失败时，作业写本次 operationId 的固定失败结果；已有匹配结果则让位。
    expect(action).toContain('$failed = $true')
    expect(action).toContain('$existing.operationId -eq')
    expect(action).toContain('"phase":"job"')
    expect(action).toContain('"errorCode":"installer"')
    expect(action).toContain('last-operation.json')
    expect(action).toContain('KK_UPDATE_PERMIT_TIMEOUT_SECONDS')
    expect(registration).not.toContain('New-ScheduledTaskTrigger')
    await result.releasePermit!()
  })

  it.each(['linux', 'win32'] as const)('explicitly clears inherited proxy for a direct %s job', async (platform) => {
    // The service manager may have a different environment from the daemon.
    vi.stubEnv('HTTPS_PROXY', 'http://host-proxy.invalid:3128')
    const { home, config } = await fixture(null)
    const adapter = deps(home, 'installer')
    adapter.platform = platform
    if (platform === 'win32') {
      adapter.network.withResponse = async (_url, _init, _signal, use) =>
        use({ statusCode: 200, statusMessage: 'OK', headers: {}, body: Readable.from(['installer']), async dispose() {} })
    }
    adapter.launch = async (file, args) => {
      if (platform === 'linux') {
        expect(args).toContain('-i')
        expect(args).toContain('https_proxy=')
        expect(args).toContain('NO_PROXY=')
        expect(args.join(' ')).not.toContain('host-proxy.invalid')
        await writeFile(join(args[args.indexOf('bash') + 1]!, '..', 'ready'), '')
      } else {
        const { action } = decodeWindows(args)
        expect(action).toContain("$env:HTTPS_PROXY = ''")
        expect(action).toContain("$env:NO_PROXY = ''")
        expect(action).not.toContain('host-proxy.invalid')
        const ready = /\[IO\.File\]::WriteAllText\('([^']+[/\\]ready)'/.exec(action)
        await writeFile(ready![1]!, '')
      }
    }
    expect((await prepareInstaller(command, config, AbortSignal.timeout(5000), adapter)).phase).toBe('PREPARED')
  })
})
