import { WebSocketServer, type WebSocket } from 'ws'
import type { AddressInfo } from 'node:net'
import { afterEach, describe, expect, it } from 'vitest'

import {
  createWebSocketTransport,
  MANDATORY_EXTENSION,
  POLICY_VIOLATION,
  type ControlConnection,
  type ControlTransportListener,
} from '../src/transport/control.js'

interface Recorder extends ControlTransportListener {
  messages: string[]
  disconnects: unknown[]
}

function recorder(): Recorder {
  const record: Recorder = {
    messages: [],
    disconnects: [],
    onMessage(message: string) {
      record.messages.push(message)
    },
    onDisconnected(cause: unknown) {
      record.disconnects.push(cause)
    },
  }
  return record
}

const servers: WebSocketServer[] = []
afterEach(async () => {
  await Promise.all(
    servers.splice(0).map(
      (server) =>
        new Promise<void>((resolve) => {
          for (const client of server.clients) {
            client.terminate()
          }
          server.close(() => resolve())
        }),
    ),
  )
})

async function listen(perMessageDeflate: boolean): Promise<{ server: WebSocketServer; url: string }> {
  const server = new WebSocketServer({ port: 0, perMessageDeflate })
  servers.push(server)
  await new Promise<void>((resolve) => server.once('listening', () => resolve()))
  const port = (server.address() as AddressInfo).port
  return { server, url: `ws://127.0.0.1:${port}` }
}

async function firstSocket(server: WebSocketServer): Promise<WebSocket> {
  if (server.clients.size > 0) {
    return server.clients.values().next().value as WebSocket
  }
  return new Promise<WebSocket>((resolve) => server.once('connection', (socket) => resolve(socket)))
}

describe('createWebSocketTransport options', () => {
  it('rejects a non-positive inbound size bound', () => {
    expect(() => createWebSocketTransport('ws://127.0.0.1:1', { maxInboundTextBytes: 0 })).toThrow(
      /maxInboundTextBytes must be positive/,
    )
  })
})

describe('mandatory permessage-deflate', () => {
  it('rejects the connection when the server does not negotiate the extension', async () => {
    const { url } = await listen(false)
    const record = recorder()
    const transport = createWebSocketTransport(url)
    await expect(transport.connect(record)).rejects.toThrow(/permessage-deflate/)
    expect(record.disconnects.length).toBe(1)
    expect(MANDATORY_EXTENSION).toBe(1010)
  })
})

describe('frame policy', () => {
  it('rejects binary frames and ignores subsequent frames', async () => {
    const { server, url } = await listen(true)
    const record = recorder()
    const connection = await createWebSocketTransport(url).connect(record)
    const socket = await firstSocket(server)
    socket.send(Buffer.from([1, 2, 3]))
    socket.send('late-text')
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(record.messages).toEqual([])
    expect(record.disconnects.length).toBe(1)
    expect(connection.isOpen()).toBe(false)
    expect(POLICY_VIOLATION).toBe(1008)
  })

  it('delivers text frames and reports a normal server close', async () => {
    const { server, url } = await listen(true)
    const record = recorder()
    await createWebSocketTransport(url).connect(record)
    const socket = await firstSocket(server)
    socket.send('hello')
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(record.messages).toEqual(['hello'])
    socket.close()
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(record.disconnects.length).toBe(1)
  })
})

describe('connection failure and closed send', () => {
  it('rejects the handshake when the socket errors before opening', async () => {
    const record = recorder()
    // 未监听端口：ws 在 open 之前触发 error。
    await expect(createWebSocketTransport('ws://127.0.0.1:1').connect(record)).rejects.toThrow()
    expect(record.disconnects.length).toBe(1)
  })

  it('rejects sendText after the connection is closed', async () => {
    const { url } = await listen(true)
    const record = recorder()
    const connection: ControlConnection = await createWebSocketTransport(url).connect(record)
    connection.close()
    await expect(connection.sendText('x')).rejects.toThrow(/websocket is closed/)
  })
})
