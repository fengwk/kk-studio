import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { main } from '../src/main.js'
import { DAEMON_VERSION } from '../src/config.js'
import type { Envelope } from '../src/protocol.js'
import * as native from '../src/native/index.js'
import { PosixProcessSupervisor } from '../src/native/posix-supervisor.js'
import * as control from '../src/transport/control.js'
import type { ProcessSpec, ProcessSupervisor } from '../src/contracts.js'
import { waitFor } from './helpers.js'
import { awaitGone, isAlive } from './native/process-test-util.js'
import {
  TOKEN,
  contentOf,
  createControlServer,
  invoke,
  lastTerminal,
  longRunningNodeCommand,
  response,
  shortNodeCommand,
  writeConfig,
} from './support/daemon-control-server.js'

/**
 * 真实 Daemon main 装配回归。
 *
 * 这里直接调用 {@link main}（而不是 CLI spawn），让真实 Runtime、真实 WebSocket transport、真实
 * native supervisor、真实 private FS/目录锁、真实 capability/IDE 装配一起被覆盖：只有虚构 loopback
 * control server，被测业务、权限与进程约束都不 mock。凭据、锁释放、在途执行收敛与信号停机都在真实进程上断言。
 *
 * 另有一组故障注入用例（fault injection）显式 mock 资源释放端口，验证释放失败绝不静默；它们只伪造
 * 「释放动作抛错」，能力/权限/进程约束仍然走真实实现，与上面的真实组合验收分开。
 */

const cleans: Array<() => void | Promise<void>> = []
afterEach(async () => {
  for (const clean of cleans.splice(0).reverse()) {
    await clean()
  }
})

type Listener = (...args: unknown[]) => void

interface RunnableMain {
  run: Promise<number>
  stdout: string[]
  stderr: string[]
  /** 触发唯一的 main 信号监听（模拟信号事件投递，不是真实 OS 信号）。 */
  triggerShutdown: () => boolean
  /** 停机、等待 main 收敛、恢复 stdout/stderr spy；始终可安全调用。 */
  stop: () => Promise<void>
  /** main 收敛后进程信号监听集合必须与进入前完全一致（证明 main 不泄漏自己的监听）。 */
  assertSignalListenersRestored: () => void
}

/**
 * 在 Vitest 进程内运行 main，并捕获 stdout/stderr。
 *
 * main 注册 SIGINT/SIGTERM 全局监听；这里记录进入前的集合，停机后校验集合完全一致。停止必须真正等待 main
 * 收敛：绝不把「等待超时」当成「已经停止」而继续删资源，回收完全依赖 production 的 finally 真正移除它自己的监听。
 */
function startMain(configPath: string): RunnableMain {
  const stdout: string[] = []
  const stderr: string[] = []
  const outSpy = vi.spyOn(process.stdout, 'write').mockImplementation((chunk: unknown) => {
    stdout.push(String(chunk))
    return true
  })
  const errSpy = vi.spyOn(process.stderr, 'write').mockImplementation((chunk: unknown) => {
    stderr.push(String(chunk))
    return true
  })

  const beforeInt = new Set<Listener>(process.listeners('SIGINT') as Listener[])
  const beforeTerm = new Set<Listener>(process.listeners('SIGTERM') as Listener[])
  let settled = false
  const run = main(['--config', configPath])
  void run.then(
    () => {
      settled = true
    },
    () => {
      settled = true
    },
  )

  const triggerShutdown = (): boolean => {
    const added = (process.listeners('SIGTERM') as Listener[]).filter((listener) => !beforeTerm.has(listener))
    if (added.length === 0) {
      return false
    }
    // 模拟信号事件投递，但不呼叫 Vitest/其它用例已有的监听：临时摘下它们，投递后原样恢复。
    const foreign = (process.listeners('SIGTERM') as Listener[]).filter((listener) => !added.includes(listener))
    for (const listener of foreign) {
      process.removeListener('SIGTERM', listener)
    }
    try {
      process.emit('SIGTERM')
    } finally {
      for (const listener of foreign) {
        process.on('SIGTERM', listener)
      }
    }
    return true
  }

  const stop = async (): Promise<void> => {
    try {
      if (settled) {
        return
      }
      // 必须真正触发 main 自己的信号监听并等它收敛。绝不 race 一个固定 sleep 后继续删资源：
      // 若 main 没有收敛就抛错（声明 Failure），由 Vitest 的 hook 超时兜住真正挂起的情况。
      const deadline = Date.now() + 10_000
      while (!settled && !triggerShutdown()) {
        if (Date.now() >= deadline) {
          throw new Error('the daemon did not register a signal listener to stop it')
        }
        await sleep(20)
      }
      await run.catch(() => undefined)
    } finally {
      outSpy.mockRestore()
      errSpy.mockRestore()
    }
  }

  cleans.push(stop)
  return {
    run,
    stdout,
    stderr,
    triggerShutdown,
    stop,
    assertSignalListenersRestored: () => {
      expect(new Set(process.listeners('SIGINT'))).toEqual(beforeInt)
      expect(new Set(process.listeners('SIGTERM'))).toEqual(beforeTerm)
    },
  }
}

function capture(): { out: string[]; err: string[]; restore: () => void } {
  const out: string[] = []
  const err: string[] = []
  const outSpy = vi.spyOn(process.stdout, 'write').mockImplementation((chunk: unknown) => {
    out.push(String(chunk))
    return true
  })
  const errSpy = vi.spyOn(process.stderr, 'write').mockImplementation((chunk: unknown) => {
    err.push(String(chunk))
    return true
  })
  return {
    out,
    err,
    restore: () => {
      outSpy.mockRestore()
      errSpy.mockRestore()
    },
  }
}

async function readFileText(file: string): Promise<string> {
  const deadline = Date.now() + 5000
  while (Date.now() < deadline) {
    try {
      return readFileSync(file, 'utf8')
    } catch {
      await sleep(10)
    }
  }
  throw new Error(`file was not written: ${file}`)
}

describe('daemon main information commands', () => {
  it('prints usage and version and exits zero', async () => {
    const help = capture()
    try {
      expect(await main(['--help'])).toBe(0)
    } finally {
      help.restore()
    }
    expect(help.out.join('')).toContain('Usage: kk-studio-daemon --config')

    const version = capture()
    try {
      expect(await main(['--version'])).toBe(0)
    } finally {
      version.restore()
    }
    expect(version.out.join('')).toContain(`kk-studio-daemon ${DAEMON_VERSION}`)
  })

  it('rejects a malformed base64 argument transport without leaking values', async () => {
    const captured = capture()
    try {
      expect(await main(['--base64-args', 'not base64!!'])).toBe(2)
    } finally {
      captured.restore()
    }
    expect(captured.err.join('')).toContain('invalid daemon argument transport')
  })

  it('validates --check-config success and failure with safe diagnostics', async () => {
    const fixture = await createControlServer()
    cleans.push(() => fixture.dispose())
    await writeConfig(fixture)
    const ok = capture()
    try {
      expect(await main(['--check-config', fixture.configPath])).toBe(0)
    } finally {
      ok.restore()
    }
    expect(ok.out.join('')).toContain('Daemon configuration is valid')

    const bad = capture()
    try {
      expect(await main(['--check-config', join(fixture.root, 'missing.json')])).toBe(1)
    } finally {
      bad.restore()
    }
    expect(bad.err.join('')).toContain('Invalid daemon configuration:')
    expect(bad.err.join('')).not.toContain(TOKEN)
  })

  it('fails closed when --config is missing or mismatched', async () => {
    const missing = capture()
    try {
      expect(await main([])).toBe(1)
    } finally {
      missing.restore()
    }
    expect(missing.err.join('')).toContain('Invalid daemon configuration:')

    const extra = capture()
    try {
      expect(await main(['--config'])).toBe(1)
    } finally {
      extra.restore()
    }
    expect(extra.err.join('')).toContain('Invalid daemon configuration:')

    const checkMissing = capture()
    try {
      expect(await main(['--check-config'])).toBe(1)
    } finally {
      checkMissing.restore()
    }
    expect(checkMissing.err.join('')).toContain('Invalid daemon configuration:')
  })
})

// 冻结契约下 native 工厂拒绝在 darwin 创建 supervisor，但装配/释放本身与平台无关：macOS 上注入平台自身的真实 POSIX
// 实现来验证装配、在途收敛与释放；生产 darwin 拒绝路径由下方 strict-confinement 围栏用例单独覆盖。
describe('daemon main real assembly', () => {
  beforeEach(() => {
    if (process.platform === 'darwin') {
      vi.spyOn(native, 'createProcessSupervisor').mockImplementation(() => new PosixProcessSupervisor())
    }
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it(
    'runs real capabilities, cancels an in-flight process and stops on a signal event releasing the lock',
    async () => {
      const fixture = await createControlServer()
      cleans.push(() => fixture.dispose())
      await writeConfig(fixture)
      const harness = startMain(fixture.configPath)

      await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'), 30_000)

      // HELLO 宣告注册凭证、能力目录版本与构建版本；凭据只出现在 HELLO payload。
      const hello = fixture.received.find((envelope) => envelope.messageType === 'HELLO') as Envelope
      const helloPayload = hello.payload as Record<string, unknown>
      expect(hello.environmentId).toBeNull()
      expect(helloPayload.registrationToken).toBe(TOKEN)
      expect(helloPayload.capabilityCatalogVersion).toBe('2')
      expect(helloPayload.daemonVersion).toBe(DAEMON_VERSION)
      expect(typeof helloPayload.daemonInstanceId).toBe('string')

      // READY 是严格 schema v5：平台归一化与真实 detectEnvironmentInfo 规范一致，不硬编码单一平台。
      const ready = fixture.received.find((envelope) => envelope.messageType === 'READY') as Envelope
      const readyPayload = ready.payload as Record<string, unknown>
      expect(readyPayload.version).toBe(5)
      expect(readyPayload.daemonVersion).toBe(DAEMON_VERSION)
      const environment = readyPayload.environment as Record<string, unknown>
      const operatingSystem = environment.operatingSystem
      expect(['windows', 'wsl', 'linux', 'macos']).toContain(operatingSystem)
      if (process.platform === 'win32') {
        expect(operatingSystem).toBe('windows')
      } else if (process.platform === 'darwin') {
        expect(operatingSystem).toBe('macos')
      } else {
        expect(['linux', 'wsl']).toContain(operatingSystem)
      }
      const architecture = environment.architecture
      if (process.arch === 'x64') {
        expect(architecture).toBe('amd64')
      } else if (process.arch === 'arm64') {
        expect(architecture).toBe('arm64')
      } else {
        throw new Error(`unexpected test architecture: ${process.arch}`)
      }
      expect(typeof environment.homeDirectory).toBe('string')

      // 真实 fs.write / fs.read：文件确实落到真实私有数据目录。
      const file = join(fixture.root, 'note.txt')
      fixture.send('INVOKE', { invocationId: 'w1', payload: invoke('fs.write', { path: file, content: 'hello main' }) })
      await waitFor(() => response(fixture, 'w1', ['COMPLETED']) !== undefined, 30_000)
      expect(await readFileText(file)).toBe('hello main')

      fixture.send('INVOKE', { invocationId: 'r1', payload: invoke('fs.read', { path: file }) })
      await waitFor(() => response(fixture, 'r1', ['COMPLETED']) !== undefined, 30_000)
      expect(contentOf(response(fixture, 'r1', ['COMPLETED']))).toContain('hello main')

      // 真实 fs.find / fs.grep 在编译 Worker 中执行。
      fixture.send('INVOKE', { invocationId: 'f1', payload: invoke('fs.find', { pattern: '*.txt', path: fixture.root }) })
      await waitFor(() => response(fixture, 'f1', ['COMPLETED']) !== undefined, 30_000)
      expect(contentOf(response(fixture, 'f1', ['COMPLETED']))).toContain('note.txt')

      fixture.send('INVOKE', { invocationId: 'g1', payload: invoke('fs.grep', { pattern: 'hello', path: fixture.root }) })
      await waitFor(() => response(fixture, 'g1', ['COMPLETED']) !== undefined, 30_000)
      expect(contentOf(response(fixture, 'g1', ['COMPLETED']))).toContain('note.txt')

      // 真实 process.exec 走 native supervisor：短命令由真实 Node 执行并自然退出。
      fixture.send('INVOKE', {
        invocationId: 'p1',
        payload: invoke('process.exec', { command: shortNodeCommand('main-exec-ok'), workdir: fixture.root }, 30_000),
      })
      await waitFor(() => response(fixture, 'p1', ['COMPLETED']) !== undefined, 60_000)
      expect(contentOf(response(fixture, 'p1', ['COMPLETED']))).toContain('main-exec-ok')

      // 长进程 + CANCEL：在途执行必须真正收敛。
      const cancelledPidFile = join(fixture.root, 'cancel.pid')
      fixture.send('INVOKE', {
        invocationId: 'long-cancel',
        payload: invoke('process.exec', { command: longRunningNodeCommand(cancelledPidFile), workdir: fixture.root }, 120_000),
      })
      await waitFor(() => existsSync(cancelledPidFile), 30_000)
      const cancelledPid = Number(readFileSync(cancelledPidFile, 'utf8').trim())
      expect(isAlive(cancelledPid)).toBe(true)
      fixture.send('CANCEL', { invocationId: 'long-cancel', payload: {} })
      await waitFor(() => lastTerminal(fixture, 'long-cancel') === 'CANCELLED', 30_000)
      expect(await awaitGone(cancelledPid, 30_000)).toBe(true)

      // 再起一个长进程，用信号事件停机：runtime 中止在途执行后才发布终止。
      const sigtermPidFile = join(fixture.root, 'sigterm.pid')
      fixture.send('INVOKE', {
        invocationId: 'long-sigterm',
        payload: invoke('process.exec', { command: longRunningNodeCommand(sigtermPidFile), workdir: fixture.root }, 120_000),
      })
      await waitFor(() => existsSync(sigtermPidFile), 30_000)
      const sigtermPid = Number(readFileSync(sigtermPidFile, 'utf8').trim())
      expect(isAlive(sigtermPid)).toBe(true)

      expect(harness.triggerShutdown()).toBe(true)
      const code = await harness.run
      expect(code).toBe(0)

      expect(await awaitGone(sigtermPid, 30_000)).toBe(true)

      // 目录锁已释放：可以重新取得。
      const lock = await native.acquireDirectoryLock(fixture.dataDir)
      await lock.close()

      // 凭据绝不进入进程输出。
      expect(harness.stdout.join('')).not.toContain(TOKEN)
      expect(harness.stderr.join('')).not.toContain(TOKEN)
      // main 收敛后没有留下自己的信号监听。
      harness.assertSignalListenersRestored()
    },
    180_000,
  )

  it(
    'assembles exactly the ten capability schemas and fails closed on registration rejection',
    async () => {
      const fixture = await createControlServer()
      cleans.push(() => fixture.dispose())
      await writeConfig(fixture)
      const harness = startMain(fixture.configPath)
      await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'), 30_000)

      // 资源 schema 目录就是能力目录的事实源：十项都必须被真实 main 装配。
      const schemaDir = fileURLToPath(new URL('../resources/capabilities', import.meta.url))
      const schemaIds = readdirSync(schemaDir)
        .filter((name) => name.endsWith('.schema.json'))
        .map((name) => name.replace(/\.schema\.json$/, ''))
        .sort()
      expect(schemaIds).toHaveLength(10)

      for (const capabilityId of schemaIds) {
        fixture.send('INVOKE', { invocationId: capabilityId, payload: invoke(capabilityId, {}) })
      }
      await waitFor(
        () => schemaIds.every((id) => response(fixture, id, ['COMPLETED', 'FAILED']) !== undefined),
        60_000,
      )
      for (const capabilityId of schemaIds) {
        const terminal = response(fixture, capabilityId, ['COMPLETED', 'FAILED']) as Envelope
        // 装配缺失才会 FAILED 且说明 unknown capability；已装配的能力以 COMPLETED(error=true) 返回参数错误。
        expect(terminal.messageType, `${capabilityId} must be assembled`).toBe('COMPLETED')
      }

      // 注册被拒是终态失败：main 以非零码退出并输出安全诊断。
      fixture.send('ERROR', { payload: { code: 'REGISTRATION_REJECTED', message: 'no lease' } })
      expect(await harness.run).toBe(1)
      expect(harness.stderr.join('')).toContain('daemon failed')
      expect(harness.stderr.join('')).not.toContain(TOKEN)

      const lock = await native.acquireDirectoryLock(fixture.dataDir)
      await lock.close()
      harness.assertSignalListenersRestored()
    },
    120_000,
  )

  it('fails closed when the data directory is already locked', async () => {
    const fixture = await createControlServer()
    cleans.push(() => fixture.dispose())
    await writeConfig(fixture)
    const held = await native.acquireDirectoryLock(fixture.dataDir)
    const harness = startMain(fixture.configPath)
    try {
      expect(await harness.run).toBe(1)
      expect(harness.stderr.join('')).toContain('Invalid daemon configuration:')
      harness.assertSignalListenersRestored()
    } finally {
      await held.close()
    }
  }, 60_000)
})

/**
 * 故障注入单元用例：只伪造「资源释放动作抛错」，能力/权限/进程约束仍走真实实现。
 *
 * 释放端口在真正 close 真实资源之后才抛错，因此不会留下持续重连或孤儿：锁、supervisor 都已被真实释放，
 * 只是聚合诊断必须显式暴露，绝不静默报告成功。
 */
describe('daemon main cleanup fault injection', () => {
  /** 包装真实 supervisor：close 先真正收敛，再按注入标记抛错。 */
  function spySupervisor(injectCloseFailure: () => boolean): void {
    // macOS 上生产工厂按契约拒绝 darwin：这里注入平台自身的真实 POSIX 实现，只验证释放/装配聚合，不冒充生产拒绝。
    const real = process.platform === 'darwin' ? new PosixProcessSupervisor() : native.createProcessSupervisor()
    const wrapper: ProcessSupervisor = {
      start: (spec: ProcessSpec) => real.start(spec),
      trackDescendant: real.trackDescendant?.bind(real),
      async close() {
        await real.close()
        if (injectCloseFailure()) {
          throw new Error('injected: supervisor close failed after real convergence')
        }
      },
    }
    vi.spyOn(native, 'createProcessSupervisor').mockReturnValue(wrapper)
  }

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reports an AggregateError on the registration-failure path after releasing the lock', async () => {
    const fixture = await createControlServer()
    cleans.push(() => fixture.dispose())
    await writeConfig(fixture)
    spySupervisor(() => true)
    const harness = startMain(fixture.configPath)
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'), 30_000)
    fixture.send('ERROR', { payload: { code: 'REGISTRATION_REJECTED', message: 'no lease' } })

    await expect(harness.run).rejects.toBeInstanceOf(AggregateError)
    // 释放失败绝不静默：锁与 native 资源都已真实释放，可再次取得。
    const lock = await native.acquireDirectoryLock(fixture.dataDir)
    await lock.close()
    harness.assertSignalListenersRestored()
  }, 60_000)

  it('reports an AggregateError on the normal-shutdown path after releasing the lock', async () => {
    const fixture = await createControlServer()
    cleans.push(() => fixture.dispose())
    await writeConfig(fixture)
    spySupervisor(() => true)
    const harness = startMain(fixture.configPath)
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'), 30_000)
    expect(harness.triggerShutdown()).toBe(true)

    await expect(harness.run).rejects.toBeInstanceOf(AggregateError)
    const lock = await native.acquireDirectoryLock(fixture.dataDir)
    await lock.close()
    harness.assertSignalListenersRestored()
  }, 60_000)

  it('aggregates the primary failure together with cleanup failures', async () => {
    const fixture = await createControlServer()
    cleans.push(() => fixture.dispose())
    await writeConfig(fixture)
    // 主失败：装配阶段创建 IDE 服务时抛错，finally 仍需释放已装配资源并聚合诊断。
    vi.spyOn(await import('../src/ide/service.js'), 'createIdeService').mockRejectedValue(
      new Error('injected: ide assembly failed'),
    )
    spySupervisor(() => true)

    const harness = startMain(fixture.configPath)
    const error = await harness.run.then(
      () => undefined,
      (rejection: unknown) => rejection,
    )
    expect(error).toBeInstanceOf(AggregateError)
    expect((error as AggregateError).errors.length).toBeGreaterThanOrEqual(2)
    const lock = await native.acquireDirectoryLock(fixture.dataDir)
    await lock.close()
    harness.assertSignalListenersRestored()
  }, 60_000)
})

/**
 * macOS 严格收敛围栏：工厂拒绝 darwin 时，Daemon 必须 fail-closed——在任何本地副作用之前失败、不建立控制面、不启动用户进程，
 * 并以 `daemon failed:` 输出固定平台原因。这里在 Linux 上固定平台为 darwin 驱动控制流，不冒充真实 macOS 验收。
 */
describe('daemon main strict-confinement fence (macOS)', () => {
  it(
    'refuses darwin before side effects and surfaces the fixed platform diagnostic',
    async () => {
      const fixture = await createControlServer()
      cleans.push(() => fixture.dispose())
      await writeConfig(fixture)

      const beforeInt = new Set<Listener>(process.listeners('SIGINT') as Listener[])
      const beforeTerm = new Set<Listener>(process.listeners('SIGTERM') as Listener[])
      const supervisorSpy = vi.spyOn(native, 'createProcessSupervisor')
      const transportSpy = vi.spyOn(control, 'createWebSocketTransport')
      const platformSpy = vi.spyOn(process, 'platform', 'get').mockReturnValue('darwin')
      const captured = capture()
      let code: number
      // mock 历史在 restore 时被清空，必须在 finally 还原之前抓取。
      let supervisorCalls = 0
      let supervisorResults: Array<{ type: string; value?: unknown }> = []
      let transportCalls = 0
      try {
        code = await main(['--config', fixture.configPath])
        supervisorCalls = supervisorSpy.mock.calls.length
        supervisorResults = supervisorSpy.mock.results.map((result) => ({ type: result.type, value: result.value }))
        transportCalls = transportSpy.mock.calls.length
      } finally {
        captured.restore()
        platformSpy.mockRestore()
        supervisorSpy.mockRestore()
        transportSpy.mockRestore()
      }

      expect(code).toBe(1)
      expect(captured.out.join('')).toBe('')
      // 真实到达 runDaemon 最前的 native 工厂并抛出 UnsupportedPlatformError，而不是任意早期失败。
      expect(supervisorCalls).toBe(1)
      expect(supervisorResults[0]?.type).toBe('throw')
      const rejection = supervisorResults[0]?.value as Error | undefined
      expect(rejection?.name).toBe('UnsupportedPlatformError')
      expect(rejection?.message).toContain('strict native process confinement is unavailable on darwin')
      // 拒绝在任何装配之前：控制面传输工厂未被调用（补充：control server 未收到任何消息）。
      expect(transportCalls).toBe(0)
      expect(fixture.received).toHaveLength(0)
      // operator 看到固定平台原因；不回显注册凭证或路径。
      const stderr = captured.err.join('')
      expect(stderr).toContain('daemon failed:')
      expect(stderr).toContain('strict native process confinement is unavailable on darwin')
      expect(stderr).toContain('setsid')
      expect(stderr).not.toContain(TOKEN)
      expect(stderr).not.toContain(fixture.root)
      expect(new Set(process.listeners('SIGINT'))).toEqual(beforeInt)
      expect(new Set(process.listeners('SIGTERM'))).toEqual(beforeTerm)
      // 围栏在 mkdir/lock 之前：未创建目录锁文件。
      expect(existsSync(join(fixture.dataDir, 'daemon.lock'))).toBe(false)
      const lock = await native.acquireDirectoryLock(fixture.dataDir)
      await lock.close()
    },
    60_000,
  )
})
