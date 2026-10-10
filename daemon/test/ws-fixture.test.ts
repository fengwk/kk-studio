import { WebSocketServer, type WebSocket } from 'ws'
import type { AddressInfo } from 'node:net'
import { afterEach, describe, expect, it } from 'vitest'

import type { CapabilityExecutor } from '../src/contracts.js'
import { decodeEnvelope, encodeInvoke, type Envelope, type MessageType } from '../src/protocol.js'
import { Runtime } from '../src/runtime.js'
import { createWebSocketTransport } from '../src/transport/control.js'
import { fakeProcessSupervisor, serverMessage, testConfig, waitFor } from './helpers.js'

const ENV = '11111111-2222-4333-8444-555555555555'
const welcomePayload = {
  environmentId: ENV,
  name: 'env',
  maxResourceBytes: 1024 * 1024,
  temporaryResourceTtlSeconds: 60,
  temporaryResourceCleanupIntervalSeconds: 30,
}
const environment = {
  operatingSystem: 'linux' as const,
  timeZone: 'UTC',
  userName: 'dev',
  homeDirectory: '/home/dev',
  note: 'Linux environment.',
  tempDirectory: '/tmp',
}

interface Fixture {
  url: string
  received: Envelope[]
  sockets: WebSocket[]
  send(messageType: MessageType, options?: { invocationId?: string | null; payload?: Record<string, unknown> }): void
  closeSocket(): void
  close(): Promise<void>
  serverSocketExtensions(): string
}

async function createFixture(): Promise<Fixture> {
  const server = new WebSocketServer({ port: 0, perMessageDeflate: true })
  await new Promise<void>((resolve) => server.once('listening', () => resolve()))
  const port = (server.address() as AddressInfo).port
  const received: Envelope[] = []
  const sockets: WebSocket[] = []
  let lastExtensions = ''

  server.on('connection', (socket) => {
    sockets.push(socket)
    lastExtensions = socket.extensions
    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        return
      }
      const envelope = decodeEnvelope(data.toString())
      received.push(envelope)
      if (envelope.messageType === 'HELLO') {
        socket.send(serverMessage('WELCOME', { environmentId: ENV, payload: welcomePayload }))
      }
    })
  })

  return {
    url: `ws://127.0.0.1:${port}`,
    received,
    sockets,
    send(messageType, options = {}) {
      const socket = sockets[sockets.length - 1]
      socket?.send(serverMessage(messageType, { environmentId: ENV, ...options }))
    },
    closeSocket() {
      sockets[sockets.length - 1]?.close()
    },
    async close() {
      for (const socket of sockets) {
        socket.close()
      }
      await new Promise<void>((resolve) => server.close(() => resolve()))
    },
    serverSocketExtensions() {
      return lastExtensions
    },
  }
}

function createRuntime(fixture: Fixture, capabilities: Record<string, CapabilityExecutor>): Runtime {
  return new Runtime({
    config: testConfig(),
    transport: createWebSocketTransport(fixture.url),
    capabilities,
    processes: fakeProcessSupervisor,
    environment,
    initialReconnectDelayMillis: 20,
    maxReconnectDelayMillis: 50,
  })
}

let cleanup: (() => Promise<void>) | null = null
afterEach(async () => {
  if (cleanup !== null) {
    await cleanup()
    cleanup = null
  }
})

describe('real websocket transport', () => {
  it('negotiates permessage-deflate and completes a handshake', async () => {
    const fixture = await createFixture()
    const runtime = createRuntime(fixture, { 'fs.read': async (call) => ({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'ok' }] }) })
    cleanup = async () => {
      runtime.close()
      await fixture.close()
    }
    runtime.start()
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'))
    expect(fixture.serverSocketExtensions()).toContain('permessage-deflate')
    expect(runtime.getState()).toBe('READY')
  })

  it('runs INVOKE, dedups a resent INVOKE and cancels a running one', async () => {
    const fixture = await createFixture()
    let calls = 0
    let release: (() => void) | null = null
    const runtime = createRuntime(fixture, {
      'fs.read': async (call) => {
        calls++
        if (call.arguments.wait === true) {
          await new Promise<void>((resolve) => {
            release = resolve
          })
        }
        return { callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'ok' }] }
      },
    })
    cleanup = async () => {
      runtime.close()
      await fixture.close()
    }
    runtime.start()
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'))

    fixture.send('INVOKE', { invocationId: 'inv-1', payload: invokeJson({ wait: true }) })
    await waitFor(() => fixture.received.filter((envelope) => envelope.messageType === 'STARTED').length === 1)
    fixture.send('INVOKE', { invocationId: 'inv-1', payload: invokeJson({ wait: true }) })
    await waitFor(() => fixture.received.filter((envelope) => envelope.messageType === 'STARTED').length === 2)
    expect(calls).toBe(1)
    expect(fixture.received.filter((envelope) => envelope.messageType === 'STARTED')[1]?.payload).toEqual({ replayed: true })

    fixture.send('CANCEL', { invocationId: 'inv-1', payload: {} })
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'CANCELLED'))
    release?.()
  })

  it('enforces the announced resource byte budget', async () => {
    const fixture = await createFixture()
    const runtime = createRuntime(fixture, {
      'fs.read': async (call) => ({
        callId: call.invocationId,
        error: false,
        details: {},
        contents: [{ type: 'binary', bytes: new Uint8Array(2 * 1024 * 1024), mediaType: 'application/octet-stream' }],
      }),
    })
    cleanup = async () => {
      runtime.close()
      await fixture.close()
    }
    runtime.start()
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'))
    fixture.send('INVOKE', { invocationId: 'inv-1', payload: invokeJson({}) })
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'FAILED'))
  })

  it('reconnects after the server drops the connection', async () => {
    const fixture = await createFixture()
    const runtime = createRuntime(fixture, {
      'fs.read': async (call) => ({ callId: call.invocationId, error: false, details: {}, contents: [{ type: 'text', text: 'ok' }] }),
    })
    cleanup = async () => {
      runtime.close()
      await fixture.close()
    }
    runtime.start()
    await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'))
    const hellosBefore = fixture.received.filter((envelope) => envelope.messageType === 'HELLO').length
    fixture.closeSocket()
    await waitFor(() => fixture.received.filter((envelope) => envelope.messageType === 'HELLO').length > hellosBefore)
    await waitFor(() => runtime.getState() === 'READY')
    // 同实例重连复用同一 daemonInstanceId。
    const identities = new Set(fixture.received.filter((envelope) => envelope.messageType === 'HELLO').map((envelope) => envelope.payload.daemonInstanceId))
    expect(identities.size).toBe(1)
  })
})

function invokeJson(args: Record<string, unknown>): Record<string, unknown> {
  return JSON.parse(
    encodeInvoke({ capabilityId: 'fs.read', capabilityVersion: '2', arguments: args, timeoutMillis: 0 }),
  ) as Record<string, unknown>
}
