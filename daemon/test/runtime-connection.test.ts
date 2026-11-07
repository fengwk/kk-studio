import { describe, expect, it, vi } from 'vitest'

import { ConnectionLifecycle } from '../src/runtime-connection.js'
import { FakeTransport, waitFor } from './helpers.js'

function buildLifecycle(transport: FakeTransport, disconnects: Array<unknown>): ConnectionLifecycle {
  return new ConnectionLifecycle({
    transport,
    initialReconnectDelayMillis: 5,
    maxReconnectDelayMillis: 20,
    callbacks: {
      onEnvelope(): void {},
      onUpgradeFailed(): void {},
      onDisconnected(connection): void {
        disconnects.push(connection)
      },
    },
  })
}

describe('connection lifecycle', () => {
  /** 关闭旧连接可能同步触发 onDisconnected；它只能被视为同一代际的一次断开。 */
  it('closes the old generation on GOAWAY and notifies disconnect exactly once', async () => {
    const transport = new FakeTransport()
    const disconnects: Array<unknown> = []
    const lifecycle = buildLifecycle(transport, disconnects)
    lifecycle.start()
    await waitFor(() => lifecycle.getActiveConnection() !== null)
    const active = lifecycle.getActiveConnection()!
    const oldListener = transport.listener!
    const oldConnection = transport.connection!
    oldConnection.closeNotifier = () => oldListener.onDisconnected()

    oldListener.onGoaway()

    expect(oldConnection.isOpen()).toBe(false)
    expect(disconnects).toEqual([active])

    await waitFor(() => transport.connectCount >= 2)
    oldListener.onDisconnected()
    expect(disconnects).toHaveLength(1)

    const remaining = lifecycle.beginShutdown('STOPPED', null)
    remaining?.connection.close()
  })

  /** 即使旧 transport 的关闭失败，也必须完成会话清理并发起重连。 */
  it('notifies disconnect and reconnects when closing the GOAWAY connection throws', async () => {
    const transport = new FakeTransport()
    const disconnects: Array<unknown> = []
    const lifecycle = buildLifecycle(transport, disconnects)
    lifecycle.start()
    await waitFor(() => lifecycle.getActiveConnection() !== null)
    const active = lifecycle.getActiveConnection()!
    transport.connection!.close = () => {
      throw new Error('close failed')
    }
    try {
      transport.goaway()
      expect(lifecycle.getActiveConnection()).toBeNull()
      expect(disconnects).toEqual([active])
      await waitFor(() => transport.connectCount >= 2)
      expect(disconnects).toHaveLength(1)
    } finally {
      lifecycle.beginShutdown('STOPPED', null)?.connection.close()
    }
  })

  /** GOAWAY 立即重连；若该次尝试失败，后续延迟仍按既有退避增长而不是停在 0。 */
  it('does not reset reconnect backoff to zero after an immediate GOAWAY reconnect fails', async () => {
    vi.useFakeTimers()
    try {
      const transport = new FakeTransport()
      const disconnects: Array<unknown> = []
      const lifecycle = buildLifecycle(transport, disconnects)
      lifecycle.start()
      await vi.advanceTimersByTimeAsync(0)
      expect(transport.connectCount).toBe(1)
      expect(lifecycle.getActiveConnection()).not.toBeNull()

      transport.connect = async () => {
        transport.connectCount++
        throw new Error('reconnect failed')
      }

      transport.goaway()
      await vi.advanceTimersByTimeAsync(0)
      expect(transport.connectCount).toBe(2)

      // GOAWAY 自身不推进退避；立即重连失败后仍按断开前的 5ms 等待，而不是 0ms 重试。
      await vi.advanceTimersByTimeAsync(4)
      expect(transport.connectCount).toBe(2)
      await vi.advanceTimersByTimeAsync(1)
      expect(transport.connectCount).toBe(3)

      lifecycle.beginShutdown('STOPPED', null)
    } finally {
      vi.useRealTimers()
    }
  })
})
