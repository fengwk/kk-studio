import { lstatSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { dirname } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { loadDaemonConfig, serializeDaemonCache } from '../src/config.js'
import {
  daemonConfigurationOf,
  EnvironmentConfigRuntime,
  type EnvironmentConfigRuntimeOptions,
  type PreparedEnvironmentConfig,
} from '../src/environment-config.js'
import type { DaemonConfiguration } from '../src/contracts.js'
import { verifyPrivateFile } from '../src/native/index.js'
import { RuntimeStatusTracker } from '../src/runtime-status.js'
import { testConfig } from './helpers.js'

const roots: string[] = []

afterEach(() => {
  for (const root of roots.splice(0)) {
    rmSync(root, { recursive: true, force: true })
  }
})

function initialConfig(overrides: Parameters<typeof testConfig>[0] = {}) {
  const config = testConfig(overrides)
  roots.push(config.dataDir)
  return config
}

function prepared(config: Parameters<EnvironmentConfigRuntimeOptions['prepare']>[0]): PreparedEnvironmentConfig {
  return {
    config,
    publish: vi.fn(),
    retire: vi.fn(async () => {}),
  }
}

function runtimeFor(
  initial: ReturnType<typeof initialConfig>,
  overrides: Partial<EnvironmentConfigRuntimeOptions> = {},
) {
  const tracker = new RuntimeStatusTracker('test')
  const prepareCalls: Parameters<EnvironmentConfigRuntimeOptions['prepare']>[0][] = []
  const preparePrevious: Parameters<EnvironmentConfigRuntimeOptions['prepare']>[1][] = []
  const defaultPrepare = async (config: Parameters<EnvironmentConfigRuntimeOptions['prepare']>[0]) => prepared(config)
  const onApplied = vi.fn()
  const onCandidate = vi.fn()
  const onCandidateAborted = vi.fn()
  const report = vi.fn()
  const tracked: Promise<void>[] = []
  const { prepare: customPrepare, ...rest } = overrides
  const effective: EnvironmentConfigRuntimeOptions = {
    paths: {
      configPath: initial.configPath,
      dataDir: initial.dataDir,
      tokenPath: initial.tokenPath,
      distributionDir: initial.distributionDir,
    },
    environmentId: initial.environmentId,
    initial,
    tracker,
    prepare: async (config, previous) => {
      prepareCalls.push(config)
      preparePrevious.push(previous)
      return (customPrepare ?? defaultPrepare)(config, previous)
    },
    onCandidate,
    onCandidateAborted,
    onApplied,
    report,
    track: (promise) => {
      tracked.push(promise)
    },
    ...rest,
  }
  const runtime = new EnvironmentConfigRuntime(effective)
  return {
    runtime,
    tracker,
    prepareCalls,
    preparePrevious,
    onCandidate: effective.onCandidate,
    onCandidateAborted: effective.onCandidateAborted,
    onApplied: effective.onApplied,
    report: effective.report,
    tracked,
  }
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void
  const promise = new Promise<void>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('EnvironmentConfigRuntime', () => {
  // 启动时缓存版本相同也必须真正完成一次校验/准备，并原子写回私有缓存。
  it('initializes the same cached version and atomically persists the private cache', async () => {
    const initial = initialConfig()
    const { runtime, tracker, prepareCalls, preparePrevious, onApplied } = runtimeFor(initial)

    const result = await runtime.submit(initial.configVersion, daemonConfigurationOf(initial))

    expect(result).toMatchObject({ kind: 'applied', config: { configVersion: 0n } })
    expect(prepareCalls).toHaveLength(1)
    expect(preparePrevious).toEqual([null])
    expect(onApplied).toHaveBeenCalledWith(initial, runtime.config)
    expect(runtime.hasAppliedConfiguration).toBe(true)
    expect(tracker.getConfiguration()).toEqual({
      appliedVersion: '0',
      proxyUrl: null,
      error: null,
    })
    expect(readFileSync(initial.configPath, 'utf8')).toBe(serializeDaemonCache(runtime.config))
    await verifyPrivateFile(initial.configPath)
    if (process.platform !== 'win32') {
      expect(lstatSync(initial.configPath).mode & 0o777).toBe(0o600)
    }
    expect(loadDaemonConfig(initial.configPath).configVersion).toBe(0n)
    expect(readdirSync(dirname(initial.configPath)).filter((name) => name.includes('.tmp'))).toEqual([])
  })

  // 首次应用传 null 表示尚无运行快照；初始化后变更才以已发布快照为 previous。
  it('passes null previous on first application and the applied snapshot afterwards', async () => {
    const initial = initialConfig()
    const { runtime, preparePrevious } = runtimeFor(initial)
    await runtime.submit(0n, daemonConfigurationOf(initial))
    const first = runtime.config

    await runtime.submit(1n, { ...daemonConfigurationOf(initial), proxyUrl: 'http://proxy.invalid:8080' })

    expect(preparePrevious).toHaveLength(2)
    expect(preparePrevious[0]).toBeNull()
    expect(preparePrevious[1]).toBe(first)
  })

  // 内容相同的新版本只推进确认版本，不重建网络/LSP 资源。
  it('advances a same-content version without preparing or publishing resources again', async () => {
    const initial = initialConfig()
    const { runtime, tracker, prepareCalls, onApplied } = runtimeFor(initial)
    const configuration = daemonConfigurationOf(initial)
    await runtime.submit(0n, configuration)
    const first = runtime.config

    const result = await runtime.submit(1n, configuration)

    expect(result).toMatchObject({ kind: 'applied', config: { configVersion: 1n } })
    expect(prepareCalls).toHaveLength(1)
    expect(onApplied).toHaveBeenLastCalledWith(first, runtime.config)
    expect(runtime.config).not.toBe(first)
    expect(runtime.config.proxyUrl).toBe(first.proxyUrl)
    expect(tracker.getConfiguration()).toMatchObject({ appliedVersion: '1', error: null })
    expect(loadDaemonConfig(initial.configPath).configVersion).toBe(1n)
  })

  // 未初始化时重复的同版本请求必须共享实际应用结果，不能提前返回 stale 让上层误发 READY。
  it('coalesces concurrent same-version submissions before initialization', async () => {
    const initial = initialConfig()
    const gate = deferred()
    const started = deferred()
    const { runtime, prepareCalls, onApplied } = runtimeFor(initial, {
      prepare: async (config) => {
        started.resolve()
        await gate.promise
        return prepared(config)
      },
    })
    const configuration = { ...daemonConfigurationOf(initial), proxyUrl: 'http://proxy.invalid:8080' }
    const first = runtime.submit(1n, configuration)
    await started.promise
    const duplicate = runtime.submit(1n, configuration)
    await expect(runtime.submit(1n, { ...configuration, proxyUrl: 'http://other.invalid:8080' })).rejects.toThrow(
      /same environment config version/,
    )

    gate.resolve()
    await expect(first).resolves.toMatchObject({ kind: 'applied', config: { configVersion: 1n } })
    await expect(duplicate).resolves.toMatchObject({ kind: 'applied', config: { configVersion: 1n } })
    expect(prepareCalls).toHaveLength(1)
    expect(onApplied).toHaveBeenCalledTimes(1)
    expect(runtime.hasAppliedConfiguration).toBe(true)
  })

  // 串行队列只保留最高待版本；未初始化时被取代的等待者跟随最高版本收敛而不是收到 stale。
  it('keeps only the highest pending version while applying the active one', async () => {
    const initial = initialConfig()
    const gate = deferred()
    const started = deferred()
    const { runtime, prepareCalls } = runtimeFor(initial, {
      prepare: async (config) => {
        if (config.configVersion === 1n) {
          started.resolve()
          await gate.promise
        }
        return prepared(config)
      },
    })
    const base = daemonConfigurationOf(initial)
    const first = runtime.submit(1n, { ...base, proxyUrl: 'http://one.invalid:8080' })
    await started.promise
    const lowerPending = runtime.submit(2n, { ...base, proxyUrl: 'http://two.invalid:8080' })
    const highest = runtime.submit(3n, { ...base, proxyUrl: 'http://three.invalid:8080' })

    gate.resolve()
    await expect(first).resolves.toMatchObject({ kind: 'applied', config: { configVersion: 1n } })
    await expect(lowerPending).resolves.toMatchObject({ kind: 'applied', config: { configVersion: 3n } })
    await expect(highest).resolves.toMatchObject({ kind: 'applied', config: { configVersion: 3n } })
    expect(prepareCalls.map((config) => config.configVersion)).toEqual([1n, 3n])
    expect(runtime.appliedVersion).toBe(3n)
  })

  // 已初始化后，低版本/重复版本走 stale/confirmed，替换 pending 与活动任务时也不重建资源。
  it('confirms equal versions and rejects stale versions after initialization', async () => {
    const initial = initialConfig()
    const gate = deferred()
    const started = deferred()
    const { runtime, prepareCalls } = runtimeFor(initial, {
      prepare: async (config) => {
        if (config.configVersion === 2n) {
          started.resolve()
          await gate.promise
        }
        return prepared(config)
      },
    })
    const base = daemonConfigurationOf(initial)
    const proxied = (port: string): DaemonConfiguration => ({ ...base, proxyUrl: `http://${port}.invalid:8080` })
    await runtime.submit(0n, base)
    const second = runtime.submit(2n, proxied('two'))
    await started.promise
    const fourth = runtime.submit(4n, proxied('four'))
    const third = runtime.submit(3n, proxied('three'))
    const fifth = runtime.submit(5n, proxied('five'))
    await expect(fourth).resolves.toMatchObject({ kind: 'stale' })
    await expect(third).resolves.toMatchObject({ kind: 'stale' })
    await expect(runtime.submit(1n, proxied('one'))).resolves.toMatchObject({ kind: 'stale' })

    gate.resolve()
    await expect(second).resolves.toMatchObject({ kind: 'applied', config: { configVersion: 2n } })
    await expect(fifth).resolves.toMatchObject({ kind: 'applied', config: { configVersion: 5n } })
    expect(prepareCalls.map((config) => config.configVersion)).toEqual([0n, 2n, 5n])

    await expect(runtime.submit(5n, proxied('five'))).resolves.toMatchObject({
      kind: 'confirmed',
      config: { configVersion: 5n },
    })
    await expect(runtime.submit(4n, proxied('four'))).resolves.toMatchObject({ kind: 'stale', config: { configVersion: 5n } })
    await expect(runtime.submit(5n, proxied('three'))).rejects.toThrow(/same environment config version/)
  })

  // 启动缓存版本尚未真正初始化时，服务端回退到更旧版本必须明确失败，不能拿未校验缓存放行。
  it('rejects a version older than the bootstrap cache before initialization', async () => {
    const initial = initialConfig()
    const stale = { ...initial, configVersion: 5n }
    const { runtime } = runtimeFor(stale)

    await expect(runtime.submit(4n, daemonConfigurationOf(stale))).resolves.toEqual({
      kind: 'fatal',
      message: 'Studio configuration version is older than the daemon cache',
    })
    expect(runtime.hasAppliedConfiguration).toBe(false)
  })

  // prepare 返回了错版本时必须清理候选并报 VALIDATION_FAILED，绝不能发布。
  it('retires and reports a prepared candidate with the wrong version', async () => {
    const initial = initialConfig()
    const retire = vi.fn(async () => {})
    const publish = vi.fn()
    const report = vi.fn()
    const { runtime } = runtimeFor(initial, {
      prepare: async (config) => ({
        config: { ...config, configVersion: config.configVersion + 1n },
        publish,
        retire,
      }),
      report,
    })

    const result = await runtime.submit(1n, { ...daemonConfigurationOf(initial), proxyUrl: 'http://proxy.invalid:8080' })

    expect(result).toMatchObject({ kind: 'failed', version: 1n, code: 'VALIDATION_FAILED' })
    expect(retire).toHaveBeenCalledTimes(1)
    expect(publish).not.toHaveBeenCalled()
    expect(report).toHaveBeenCalledWith(expect.any(Error))
    expect(runtime.hasAppliedConfiguration).toBe(false)
  })

  // publish 返回的异步清理失败必须进入诊断；publish 同步抛错则不得发布快照并清理候选。
  it('reports asynchronous publish cleanup failures and aborts a synchronous publish failure', async () => {
    const initial = initialConfig()
    const cleanupError = new Error('publish cleanup boom')
    const cleanup = runtimeFor(initial, {
      prepare: async (config) => ({
        config,
        publish: () => Promise.reject(cleanupError),
        retire: async () => {},
      }),
    })
    await expect(cleanup.runtime.submit(1n, { ...daemonConfigurationOf(initial), proxyUrl: 'http://one.invalid:8080' }))
      .resolves.toMatchObject({ kind: 'applied', config: { configVersion: 1n } })
    await Promise.all(cleanup.tracked)
    expect(cleanup.report).toHaveBeenCalledWith(cleanupError)

    const publishError = new Error('publish boom')
    const retire = vi.fn(async () => {})
    const failing = runtimeFor(initial, {
      prepare: async (config) => ({
        config,
        publish: () => {
          throw publishError
        },
        retire,
      }),
    })
    const result = await failing.runtime.submit(2n, { ...daemonConfigurationOf(initial), proxyUrl: 'http://two.invalid:8080' })
    expect(result).toEqual({ kind: 'fatal', message: 'prepared environment resources could not be published' })
    expect(retire).toHaveBeenCalledTimes(1)
    expect(failing.report).toHaveBeenCalledWith(publishError)
    expect(failing.runtime.hasAppliedConfiguration).toBe(false)
  })

  // 候选校验失败必须保留旧可用快照，后续修正版本能清除错误并继续热应用。
  it('preserves the previous snapshot on validation failure and recovers on correction', async () => {
    const initial = initialConfig()
    const { runtime, tracker, prepareCalls } = runtimeFor(initial)
    await runtime.submit(0n, daemonConfigurationOf(initial))
    const previous = runtime.config
    const cache = readFileSync(initial.configPath, 'utf8')

    const failed = await runtime.submit(1n, { ...daemonConfigurationOf(initial), bashExecutable: '/definitely/missing/bash' })

    expect(failed).toMatchObject({ kind: 'failed', version: 1n, code: 'VALIDATION_FAILED', field: 'bashExecutable' })
    expect(runtime.config).toBe(previous)
    expect(readFileSync(initial.configPath, 'utf8')).toBe(cache)
    expect(tracker.getConfiguration()).toMatchObject({
      appliedVersion: '0',
      error: { version: '1', code: 'VALIDATION_FAILED', field: 'bashExecutable' },
    })
    expect(prepareCalls).toHaveLength(1)

    const corrected = await runtime.submit(2n, { ...daemonConfigurationOf(initial), proxyUrl: 'http://proxy.invalid:8080' })
    expect(corrected).toMatchObject({ kind: 'applied', config: { configVersion: 2n } })
    expect(tracker.getConfiguration()).toMatchObject({ appliedVersion: '2', error: null })
  })

  // 候选准入钩子必须早于 prepare；准备失败后恢复准备前状态且只通知一次。
  it('notifies candidate suspension before preparation and aborts it on failure', async () => {
    const initial = initialConfig()
    const gate = deferred()
    const started = deferred()
    const events: string[] = []
    const { runtime, onCandidate, onCandidateAborted } = runtimeFor(initial, {
      prepare: async () => {
        events.push('prepare')
        started.resolve()
        await gate.promise
        throw new Error('prepare failed')
      },
      onCandidate: vi.fn(() => {
        events.push('candidate')
      }),
      onCandidateAborted: vi.fn(() => {
        events.push('aborted')
      }),
    })

    const applying = runtime.submit(1n, { ...daemonConfigurationOf(initial), proxyUrl: 'http://proxy.invalid:8080' })
    await started.promise
    expect(events).toEqual(['candidate', 'prepare'])
    gate.resolve()
    await expect(applying).resolves.toMatchObject({ kind: 'failed', code: 'VALIDATION_FAILED' })
    expect(events).toEqual(['candidate', 'prepare', 'aborted'])
    expect(onCandidate).toHaveBeenCalledTimes(1)
    expect(onCandidateAborted).toHaveBeenCalledTimes(1)
  })

  // 缓存写入失败不得发布候选；候选清理失败也必须进入诊断，而不是静默吞掉。
  it('does not publish a candidate when persistence fails and reports cleanup failures', async () => {
    const initial = initialConfig()
    const report = vi.fn()
    const publish = vi.fn()
    const retireError = new Error('retire boom')
    const retire = vi.fn(async () => {
      throw retireError
    })
    const { runtime, tracker } = runtimeFor(initial, {
      paths: {
        configPath: `${initial.configPath}.missing/daemon.json`,
        dataDir: initial.dataDir,
        tokenPath: initial.tokenPath,
        distributionDir: initial.distributionDir,
      },
      prepare: async (config) => ({ config, publish, retire }),
      report,
    })

    const result = await runtime.submit(0n, daemonConfigurationOf(initial))

    expect(result).toMatchObject({ kind: 'failed', code: 'PERSIST_FAILED' })
    expect(publish).not.toHaveBeenCalled()
    expect(retire).toHaveBeenCalledTimes(1)
    expect(report).toHaveBeenCalledWith(retireError)
    expect(runtime.hasAppliedConfiguration).toBe(false)
    expect(tracker.getConfiguration()).toMatchObject({
      appliedVersion: null,
      error: { version: '0', code: 'PERSIST_FAILED' },
    })
  })

  // close 必须等待正在准备的候选收敛、清理候选且禁止发布，不能只清掉 pending。
  it('waits for the active candidate, retires it and never publishes after close', async () => {
    const initial = initialConfig()
    const gate = deferred()
    const started = deferred()
    const publish = vi.fn()
    const retire = vi.fn(async () => {})
    const { runtime } = runtimeFor(initial, {
      prepare: async (config) => {
        started.resolve()
        await gate.promise
        return { config, publish, retire }
      },
    })
    const applying = runtime.submit(1n, { ...daemonConfigurationOf(initial), proxyUrl: 'http://proxy.invalid:8080' })
    await started.promise
    const closing = runtime.close()
    let closed = false
    void closing.then(() => {
      closed = true
    })
    await Promise.resolve()
    expect(closed).toBe(false)

    gate.resolve()
    await expect(applying).resolves.toMatchObject({ kind: 'fatal', message: 'configuration runtime is closed' })
    await closing
    expect(closed).toBe(true)
    expect(retire).toHaveBeenCalledTimes(1)
    expect(publish).not.toHaveBeenCalled()
    expect(runtime.hasAppliedConfiguration).toBe(false)
  })

  // close 立即拒绝尚未开始的 pending；已经关闭后提交也确定失败。
  it('rejects pending work immediately on close', async () => {
    const initial = initialConfig()
    const gate = deferred()
    const started = deferred()
    const { runtime, prepareCalls } = runtimeFor(initial, {
      prepare: async (config) => {
        started.resolve()
        await gate.promise
        return prepared(config)
      },
    })
    const base = daemonConfigurationOf(initial)
    const active = runtime.submit(1n, { ...base, proxyUrl: 'http://one.invalid:8080' })
    await started.promise
    const pending = runtime.submit(2n, { ...base, proxyUrl: 'http://two.invalid:8080' })
    const closing = runtime.close()
    await expect(pending).resolves.toEqual({ kind: 'fatal', message: 'configuration runtime is closed' })
    await expect(runtime.submit(3n, base)).resolves.toEqual({
      kind: 'fatal',
      message: 'configuration runtime is closed',
    })

    gate.resolve()
    await expect(active).resolves.toEqual({ kind: 'fatal', message: 'configuration runtime is closed' })
    await closing
    expect(prepareCalls).toHaveLength(1)
  })
})
