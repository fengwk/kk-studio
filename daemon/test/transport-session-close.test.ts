import { EventEmitter } from 'node:events'
import http2, { constants as h2constants, type ServerHttp2Session } from 'node:http2'
import { Duplex } from 'node:stream'
import { describe, expect, it, vi } from 'vitest'

import {
  abortControlSession,
  closeControlSession,
  guardControlStream,
} from '../src/transport/index.js'

interface FakeWebSocket extends EventEmitter {
  close: ReturnType<typeof vi.fn>
  terminate: ReturnType<typeof vi.fn>
}

/**
 * 模拟 createWebSocketStream：对端 close 时 push(null)，error 时以错误销毁 Duplex。ws 的
 * _final 在底层 socket 已消失时直接返回，shutdown 会永久挂起；这里保持同样的挂起状态，
 * 以稳定复现 JSStreamSocket 的在途 shutdown。
 */
function createWebSocketStreamLike(writes: Buffer[] = []): { ws: FakeWebSocket; stream: Duplex } {
  const stream = new Duplex({
    read() {},
    write(chunk: Buffer, _encoding, callback) {
      writes.push(Buffer.from(chunk))
      setImmediate(callback)
    },
    final() {},
    allowHalfOpen: false,
  })
  const ws = new EventEmitter() as FakeWebSocket
  ws.close = vi.fn()
  ws.terminate = vi.fn()
  ws.on('close', () => {
    if (!stream.destroyed) {
      stream.push(null)
    }
  })
  ws.on('error', (error: Error) => {
    if (!stream.destroyed) {
      stream.destroy(error)
    }
  })
  return { ws, stream }
}

function createSession(stream: Duplex): ServerHttp2Session {
  const session = http2.performServerHandshake(stream)
  session.on('error', () => {})
  return session
}

describe('control session close over a WebSocket duplex', () => {
  it('destroys the stream before a peer close can half-close it', async () => {
    const { ws, stream } = createWebSocketStreamLike()
    const session = createSession(stream)
    guardControlStream(ws, stream)

    ws.emit('close')

    expect(stream.destroyed).toBe(true)
    await vi.waitFor(() => {
      expect(session.destroyed).toBe(true)
    })
  })

  it('destroys the stream before a WebSocket error can reach the HTTP/2 socket', async () => {
    const { ws, stream } = createWebSocketStreamLike()
    const session = createSession(stream)
    let sessionError: Error | null = null
    session.on('error', (error) => {
      sessionError = error
    })
    guardControlStream(ws, stream)

    ws.emit('error', new Error('read ECONNRESET'))

    expect(stream.destroyed).toBe(true)
    await vi.waitFor(() => {
      expect(session.destroyed).toBe(true)
    })
    expect(sessionError).toBeNull()
  })

  it('aborts a live session and terminates the WebSocket', () => {
    const { ws, stream } = createWebSocketStreamLike()
    const session = createSession(stream)

    abortControlSession(session, stream, ws)

    expect(session.destroyed).toBe(true)
    expect(ws.terminate).toHaveBeenCalled()
    stream.destroy()
  })

  it('closes a live session gracefully with GOAWAY and a WebSocket close', async () => {
    const { ws, stream } = createWebSocketStreamLike()
    const session = createSession(stream)
    const goaway = vi.spyOn(session, 'goaway')

    closeControlSession(session, stream, ws, null)

    expect(goaway).toHaveBeenCalledWith(h2constants.NGHTTP2_NO_ERROR)
    expect(ws.close).toHaveBeenCalled()
    expect(ws.terminate).not.toHaveBeenCalled()

    guardControlStream(ws, stream)
    ws.emit('close')
    await vi.waitFor(() => {
      expect(session.destroyed).toBe(true)
    })
  })

  it('falls back to an abort when the peer already ended the stream', async () => {
    const { ws, stream } = createWebSocketStreamLike()
    const session = createSession(stream)
    guardControlStream(ws, stream)
    ws.emit('close')
    await vi.waitFor(() => {
      expect(session.destroyed).toBe(true)
    })

    closeControlSession(session, stream, ws, null)

    expect(ws.close).not.toHaveBeenCalled()
    expect(ws.terminate).toHaveBeenCalled()
  })
})
