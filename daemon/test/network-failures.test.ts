import http from 'node:http'
import net from 'node:net'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Agent } from 'undici'

import type { SystemProxyResolver } from '../src/contracts.js'
import { installOutboundNetwork, type InstalledOutboundNetwork } from '../src/network.js'

// 共享出站网络的失败收敛：只伪造 undici SDK 边界（Agent close/destroy），走真实 openResponse/withResponse/close。

/** 测试用系统回退：明确直连。 */
const systemDirect: SystemProxyResolver = {
  async resolve(): Promise<string | null> {
    return null
  },
  async close(): Promise<void> {},
}

interface Origin {
  url: string
  close(): Promise<void>
}

/** 真实本地 origin：让 dispatch 真实建立连接，再由 SDK 边界注入关闭失败。 */
async function startOrigin(body = 'origin-body'): Promise<Origin> {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'text/plain' })
    response.end(body)
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
  const port = (server.address() as net.AddressInfo).port
  return {
    url: `http://127.0.0.1:${port}`,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections?.()
        server.close(() => resolve())
      }),
  }
}

const cleanups: Array<() => Promise<void>> = []
afterEach(async () => {
  vi.restoreAllMocks()
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

/**
 * 安装真实网络 + 本地 origin；测试自行触发 close，收尾时兜底关闭（幂等，不掩盖结论也不留悬挂资源）。
 */
async function withFailureSeam<T>(
  run: (installed: InstalledOutboundNetwork, origin: Origin) => Promise<T>,
): Promise<T> {
  const origin = await startOrigin()
  const installed = await installOutboundNetwork({}, systemDirect)
  cleanups.push(origin.close)
  try {
    return await run(installed, origin)
  } finally {
    await installed.close().catch(() => undefined)
  }
}

describe('outbound network teardown failure retention', () => {
  it('retains a real dispatcher close failure from an explicit response dispose', async () => {
    await withFailureSeam(async (installed, origin) => {
      const response = await installed.network.openResponse(origin.url, {}, new AbortController().signal)
      expect(response.statusCode).toBe(200)
      const failure = new Error('dispatcher close failed')
      const spy = vi.spyOn(Agent.prototype, 'close').mockImplementation(() => Promise.reject(failure))
      try {
        // 生命周期尚未 abort：真实 close 失败必须作为 cleanup 保留，而不是被当成预期取消吞掉。
        await expect(response.dispose()).rejects.toBe(failure)
      } finally {
        spy.mockRestore()
      }
    })
  })

  it('treats an expected abort raised while shutting down as no cleanup failure', async () => {
    await withFailureSeam(async (installed, origin) => {
      await installed.network.openResponse(origin.url, {}, new AbortController().signal)
      const spy = vi.spyOn(Agent.prototype, 'destroy').mockImplementation(() =>
        Promise.reject(Object.assign(new Error('aborted'), { name: 'AbortError' })),
      )
      try {
        // close 触发的 destroy 取消属于预期中止：必须收敛，不产生 cleanup failure。
        await expect(installed.close()).resolves.toBeUndefined()
      } finally {
        spy.mockRestore()
      }
    })
  })

  it('aggregates multiple destroyed agent failures during teardown', async () => {
    await withFailureSeam(async (installed) => {
      const first = await installed.network.createWebSocketAgent('ws://127.0.0.1:1', new AbortController().signal)
      const second = await installed.network.createWebSocketAgent('ws://127.0.0.1:2', new AbortController().signal)
      const firstFailure = new Error('first agent destroy failed')
      const secondFailure = new Error('second agent destroy failed')
      const firstDestroy = vi.fn(() => {
        throw firstFailure
      })
      const secondDestroy = vi.fn(() => {
        throw secondFailure
      })
      first.agent.destroy = firstDestroy
      second.agent.destroy = secondDestroy
      const error = await installed.close().then(
        () => undefined,
        (reason: unknown) => reason,
      )
      // 每个独占 agent 都必须被真实销毁，失败逐个保留后聚合，绝不能相互覆盖。
      expect(firstDestroy).toHaveBeenCalledTimes(1)
      expect(secondDestroy).toHaveBeenCalledTimes(1)
      expect(error).toBeInstanceOf(AggregateError)
      expect([...(error as AggregateError).errors]).toEqual([firstFailure, secondFailure])
    })
  })
})

describe('outbound network failure aggregation', () => {
  it('keeps the callback failure and the cleanup failure when both fail', async () => {
    await withFailureSeam(async (installed, origin) => {
      const useFailure = new Error('callback failed')
      const cleanupFailure = new Error('cleanup failed')
      const spy = vi.spyOn(Agent.prototype, 'close').mockImplementation(() => Promise.reject(cleanupFailure))
      try {
        const error = await installed.network
          .withResponse(origin.url, {}, new AbortController().signal, async () => {
            throw useFailure
          })
          .then(
            () => undefined,
            (reason: unknown) => reason,
          )
        expect(error).toBeInstanceOf(AggregateError)
        expect([...(error as AggregateError).errors]).toEqual([useFailure, cleanupFailure])
      } finally {
        spy.mockRestore()
      }
    })
  })

  it('propagates the cleanup failure when the callback succeeded', async () => {
    await withFailureSeam(async (installed, origin) => {
      const cleanupFailure = new Error('cleanup failed after success')
      const spy = vi.spyOn(Agent.prototype, 'close').mockImplementation(() => Promise.reject(cleanupFailure))
      try {
        await expect(
          installed.network.withResponse(origin.url, {}, new AbortController().signal, async () => 'ok'),
        ).rejects.toBe(cleanupFailure)
      } finally {
        spy.mockRestore()
      }
    })
  })

  it('treats a callback rejection without a reason as cleanup only', async () => {
    await withFailureSeam(async (installed, origin) => {
      const cleanupFailure = new Error('cleanup failed without primary')
      const spy = vi.spyOn(Agent.prototype, 'close').mockImplementation(() => Promise.reject(cleanupFailure))
      try {
        // 回调以无原因拒绝（Promise.reject()）：只有 cleanup 失败时直接保留 cleanup，不能聚合出空主因。
        await expect(
          installed.network.withResponse(origin.url, {}, new AbortController().signal, () => Promise.reject()),
        ).rejects.toBe(cleanupFailure)
      } finally {
        spy.mockRestore()
      }
    })
  })
})
