import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { main } from '../src/main.js'
import * as native from '../src/native/index.js'
import { waitFor } from './helpers.js'
import { artifactFixture } from './support/update-fixture.js'
import { createControlServer, writeConfig } from './support/daemon-control-server.js'

/**
 * daemon main 的扩展装配回归。
 *
 * <p>只覆盖 Runtime 与生产 mux 数据面、受管更新报告器的接线，不重复适配器自身的 close/rebind 单元。
 * 更新入口由本文件替换为确定性桩，其余 Runtime、native、IDE 与文件锁仍走真实实现。
 */

const updateCalls = vi.hoisted(() => ({
  entries: [] as Array<{
    command: Record<string, unknown>
    reportProgress?: (progress: { downloadedBytes: number; totalBytes: number | null }) => void
  }>,
}))

vi.mock('../src/update.js', () => ({
  async handleUpdate(
    command: Record<string, unknown>,
    _config: unknown,
    _signal: AbortSignal,
    _processes: unknown,
    reportProgress?: (progress: { downloadedBytes: number; totalBytes: number | null }) => void,
  ) {
    updateCalls.entries.push({ command, reportProgress })
    reportProgress?.({ downloadedBytes: 12, totalBytes: 100 })
    return { phase: 'PREPARED' }
  },
}))

const cleans: Array<() => void | Promise<void>> = []
beforeEach(() => {
  vi.stubEnv('http_proxy', '')
  vi.stubEnv('https_proxy', '')
  vi.stubEnv('no_proxy', '')
  if (process.platform === 'darwin') {
    vi.spyOn(native, 'createSystemProxyResolver').mockReturnValue({
      async resolve() {
        return null
      },
      async close() {},
    })
  }
})
afterEach(async () => {
  for (const clean of cleans.splice(0).reverse()) {
    await clean()
  }
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

type Listener = (...args: unknown[]) => void

/**
 * 在测试进程内运行 main，并在清理阶段只投递 main 自己注册的 SIGTERM，等待其真实收敛。
 * stdout/stderr 被捕获，避免装配日志污染测试输出。
 */
function startMain(configPath: string): { run: Promise<number>; stderr: string[]; stop: () => Promise<number> } {
  const stderr: string[] = []
  const outSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
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

  let stopping: Promise<number> | null = null
  const stop = (): Promise<number> => {
    stopping ??= (async () => {
      const deadline = Date.now() + 10_000
      while (!settled) {
        const added = (process.listeners('SIGTERM') as Listener[]).filter((listener) => !beforeTerm.has(listener))
        if (added.length === 0) {
          if (Date.now() >= deadline) {
            throw new Error('the daemon did not register a signal listener to stop it')
          }
          await sleep(20)
          continue
        }
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
      }
      const code = await run
      expect(new Set(process.listeners('SIGINT'))).toEqual(beforeInt)
      expect(new Set(process.listeners('SIGTERM'))).toEqual(beforeTerm)
      return code
    })()
    return stopping
  }
  cleans.push(async () => {
    try {
      await stop()
    } finally {
      outSpy.mockRestore()
      errSpy.mockRestore()
    }
  })
  return { run, stderr, stop }
}

describe('daemon main extension assembly', () => {
  it('routes DATA_BIND through the production mux upstream and releases resources on shutdown', async () => {
    const fixture = await createControlServer()
    cleans.push(() => fixture.dispose())
    await writeConfig(fixture)
    const harness = startMain(fixture.configPath)
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'), 30_000)

    // 同一 origin 下让 streamUrl 携带与 payload 不同的票据：生产 mux service 会在打开 socket 前拒绝。
    const ticket = 'A'.repeat(43)
    const otherTicket = 'B'.repeat(43)
    fixture.send('DATA_BIND', {
      payload: {
        generation: '1',
        ticket,
        streamUrl: `${fixture.url}/api/harness/environment-data/v2/environment?ticket=${otherTicket}`,
      },
    })
    await waitFor(
      () =>
        fixture.received.some(
          (envelope) =>
            envelope.messageType === 'ERROR' &&
            (envelope.payload as { message: string }).message.includes(
              'DATA_BIND handler failed: DATA_BIND ticket does not match streamUrl',
            ),
        ),
      10_000,
    )
    expect(
      fixture.received.some(
        (envelope) =>
          envelope.messageType === 'ERROR' &&
          (envelope.payload as { message: string }).message.includes('DATA_BIND handler is not configured'),
      ),
    ).toBe(false)

    expect(await harness.stop()).toBe(0)
    const lock = await native.acquireDirectoryLock(fixture.dataDir)
    await lock.close()
  })

  it('forwards the updater progress reporter into the Runtime status', async () => {
    const fixture = await createControlServer()
    cleans.push(() => fixture.dispose())
    await writeConfig(fixture)
    const harness = startMain(fixture.configPath)
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'), 30_000)

    fixture.send('UPDATE', {
      payload: {
        operationId: artifactFixture.operationId,
        targetVersion: artifactFixture.targetVersion,
        artifactUrl: artifactFixture.accepted[0]!.artifactUrl,
        artifactSha256: artifactFixture.artifactSha256,
      },
    })
    await waitFor(
      () =>
        fixture.received.some(
          (envelope) =>
            envelope.messageType === 'RUNTIME_STATUS' &&
            (envelope.payload as { status?: { update?: { downloadedBytes?: number } } }).status?.update
              ?.downloadedBytes === 12,
        ),
      30_000,
    )
    const status = fixture.received
      .filter((envelope) => envelope.messageType === 'RUNTIME_STATUS')
      .at(-1) as { payload: { status: { update: unknown } } }
    expect(status.payload.status.update).toMatchObject({
      targetVersion: artifactFixture.targetVersion,
      downloadedBytes: 12,
      totalBytes: 100,
    })
    expect(updateCalls.entries).toHaveLength(1)
    expect(updateCalls.entries[0]?.reportProgress).toBeTypeOf('function')
    expect(updateCalls.entries[0]?.command).toMatchObject({
      operationId: artifactFixture.operationId,
      targetVersion: artifactFixture.targetVersion,
    })

    expect(await harness.stop()).toBe(0)
    const lock = await native.acquireDirectoryLock(join(fixture.dataDir))
    await lock.close()
  })
})
