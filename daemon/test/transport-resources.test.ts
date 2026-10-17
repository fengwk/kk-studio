import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'

import { describe, expect, it } from 'vitest'

import { decodeUploadCommit, decodeUploadRequest, encodeUploadTicket } from '../src/protocol.js'
import { createHttpPutObject, createResourceTransferClient, type ResourceTransferClient } from '../src/transport/resources.js'
import { tick, waitFor } from './helpers.js'

const INVOCATION_ID = 'inv-1'
const UPLOAD_ID = '99999999-8888-4777-8666-555555555555'

type TicketSpec = { state: 'PENDING' } | { state: 'READY' } | { state: 'FAILED'; message?: string }

function ticketFor(transferId: string, spec: TicketSpec): string {
  switch (spec.state) {
    case 'PENDING':
      return encodeUploadTicket({
        transferId,
        state: 'PENDING',
        uploadId: UPLOAD_ID,
        presignedPut: {
          method: 'PUT',
          url: 'https://s3.example.com/bucket/key',
          headers: { 'x-amz-acl': 'private' },
          expiresAt: '2030-01-01T00:00:00.000Z',
        },
        message: null,
      })
    case 'READY':
      return encodeUploadTicket({ transferId, state: 'READY', uploadId: UPLOAD_ID, presignedPut: null, message: null })
    case 'FAILED':
      return encodeUploadTicket({ transferId, state: 'FAILED', uploadId: null, presignedPut: null, message: spec.message ?? 'nope' })
  }
}

interface Script {
  client: ResourceTransferClient
  requests: { type: string; invocationId: string; payload: Record<string, unknown> }[]
  putCalls: number
  putBytes: number
  setScript(specs: TicketSpec[]): void
}

function makeClient(options: { putRejects?: boolean; ticketWaitMillis?: number } = {}): Script {
  const requests: Script['requests'] = []
  let script: TicketSpec[] = []
  let client: ResourceTransferClient
  const state = { putCalls: 0, putBytes: 0 }
  client = createResourceTransferClient({
    sendControl: (type, invocationId, payloadJson) => {
      const payload = JSON.parse(payloadJson) as Record<string, unknown>
      requests.push({ type, invocationId, payload })
      const spec = script.shift()
      if (spec !== undefined) {
        // 用请求中的 transferId 关联响应；在 pending 注册后异步投递票据。
        queueMicrotask(() => client.onTicket(invocationId, ticketFor(payload.transferId as string, spec)))
      }
      return true
    },
    putObject: async (_presigned, bytes) => {
      state.putCalls++
      state.putBytes += bytes.byteLength
      if (options.putRejects === true) {
        throw new Error('storage rejected')
      }
    },
    ticketWaitMillis: options.ticketWaitMillis ?? 2000,
    retryBackoffMillis: 5,
    connectionPollMillis: 5,
  })
  return {
    client,
    requests,
    get putCalls() {
      return state.putCalls
    },
    get putBytes() {
      return state.putBytes
    },
    setScript(specs: TicketSpec[]) {
      script = specs
    },
  } as Script
}

const binary = { type: 'binary' as const, bytes: new Uint8Array([1, 2, 3, 4]), mediaType: 'application/octet-stream' }

describe('resource transfer client', () => {
  it('runs REQUEST → PUT → COMMIT → READY and returns metadata only', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'PENDING' }, { state: 'READY' }])
    const result = await script.client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result).toMatchObject({ type: 'resource', uploadId: UPLOAD_ID, size: 4 })
    expect(result.sha256).toMatch(/^[0-9a-f]{64}$/)
    expect(script.putCalls).toBe(1)
    expect(script.putBytes).toBe(4)
    expect(script.requests.map((request) => request.type)).toEqual(['RESOURCE_UPLOAD_REQUEST', 'RESOURCE_UPLOAD_COMMIT'])
    const request = decodeUploadRequest(JSON.stringify(script.requests[0]?.payload))
    expect(request.size).toBe(4)
    const commit = decodeUploadCommit(JSON.stringify(script.requests[1]?.payload))
    expect(commit.uploadId).toBe(UPLOAD_ID)
    expect(commit.transferId).toBe(request.transferId)
  })

  it('retries with the same transferId and never re-uploads after a FAILED request', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'FAILED' }, { state: 'READY' }])
    const result = await script.client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
    expect(script.putCalls).toBe(0)
    expect(script.requests.map((request) => request.type)).toEqual(['RESOURCE_UPLOAD_REQUEST', 'RESOURCE_UPLOAD_REQUEST'])
    const first = decodeUploadRequest(JSON.stringify(script.requests[0]?.payload))
    const second = decodeUploadRequest(JSON.stringify(script.requests[1]?.payload))
    expect(first.transferId).toBe(second.transferId)
  })

  it('re-uploads when the commit is authoritatively rejected', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'PENDING' }, { state: 'FAILED' }, { state: 'PENDING' }, { state: 'READY' }])
    const result = await script.client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
    expect(script.putCalls).toBe(2)
    expect(script.requests.map((request) => request.type)).toEqual([
      'RESOURCE_UPLOAD_REQUEST',
      'RESOURCE_UPLOAD_COMMIT',
      'RESOURCE_UPLOAD_REQUEST',
      'RESOURCE_UPLOAD_COMMIT',
    ])
  })

  it('rejects when the invocation signal is already aborted', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    const controller = new AbortController()
    controller.abort('cancelled')
    await expect(script.client.upload(INVOCATION_ID, binary, controller.signal)).rejects.toThrow(/resource upload failed/)
  })

  it('waits for reconnect and resumes the same transfer', async () => {
    const requests: { type: string; payload: Record<string, unknown> }[] = []
    let deliver: (() => void) | null = null
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (type, invocationId, payloadJson) => {
        const payload = JSON.parse(payloadJson) as Record<string, unknown>
        requests.push({ type, payload })
        deliver = () => client.onTicket(invocationId, ticketFor(payload.transferId as string, { state: 'READY' }))
        return true
      },
      putObject: async () => {},
      ticketWaitMillis: 2000,
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    const promise = client.upload(INVOCATION_ID, binary, new AbortController().signal)
    await tick()
    // 连接不可用时不会发出控制帧。
    expect(requests.length).toBe(0)
    client.onConnectionReady()
    await waitFor(() => deliver !== null)
    deliver?.()
    const result = await promise
    expect(result.uploadId).toBe(UPLOAD_ID)
  })

  it('ignores malformed and unmatched tickets', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'READY' }])
    // 非法票据与错误 invocationId 的票据都不应破坏后续上传。
    script.client.onTicket(INVOCATION_ID, '{not json')
    script.client.onTicket('other-invocation', ticketFor('00000000-0000-4000-8000-000000000000', { state: 'READY' }))
    const result = await script.client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
  })

  it('ignores a ticket addressed to a blank invocation id', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    script.client.onTicket('   ', ticketFor('00000000-0000-4000-8000-000000000000', { state: 'READY' }))
    script.setScript([{ state: 'READY' }])
    const result = await script.client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
  })
})

describe('resource transfer resilience branches', () => {
  const binary = { type: 'binary' as const, bytes: new Uint8Array([1, 2]), mediaType: 'application/octet-stream' }

  it('rejects a non-positive timing option', () => {
    expect(() =>
      createResourceTransferClient({
        sendControl: () => true,
        putObject: async () => {},
        ticketWaitMillis: 0,
      }),
    ).toThrow(/ticketWaitMillis must be a positive integer/)
  })

  it('fails fast when the control frame cannot be sent, then succeeds on retry', async () => {
    let attempts = 0
    const requests: string[] = []
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (type, invocationId, payloadJson) => {
        attempts++
        if (attempts === 1) {
          return false
        }
        requests.push(type)
        const transferId = (JSON.parse(payloadJson) as { transferId: string }).transferId
        queueMicrotask(() => client.onTicket(invocationId, ticketFor(transferId, { state: 'READY' })))
        return true
      },
      putObject: async () => {},
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    // 第一次 sendControl 返回 false 会走退避重试；第二次放行后同一 transfer 重放成功。
    const result = await client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
    expect(requests).toEqual(['RESOURCE_UPLOAD_REQUEST'])
    expect(attempts).toBe(2)
  })

  it('aborts an upload waiting for a ticket', async () => {
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: () => true,
      putObject: async () => {},
      ticketWaitMillis: 5000,
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    const controller = new AbortController()
    const promise = client.upload(INVOCATION_ID, binary, controller.signal)
    await tick()
    controller.abort('cancelled')
    await expect(promise).rejects.toThrow(/resource upload failed/)
  })

  it('times out a ticket wait and retries under the same transfer', async () => {
    const transferIds: string[] = []
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (_type, _invocationId, payloadJson) => {
        transferIds.push((JSON.parse(payloadJson) as { transferId: string }).transferId)
        return true
      },
      putObject: async () => {},
      ticketWaitMillis: 10,
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    const controller = new AbortController()
    const promise = client.upload(INVOCATION_ID, binary, controller.signal)
    await tick(4)
    controller.abort('cancelled')
    await expect(promise).rejects.toThrow(/resource upload failed/)
    expect(new Set(transferIds).size).toBe(1)
  })

  it('waits for reconnect after a lost connection and resumes the same transfer', async () => {
    let sends = 0
    let deliver: (() => void) | null = null
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (_type, invocationId, payloadJson) => {
        sends++
        const transferId = (JSON.parse(payloadJson) as { transferId: string }).transferId
        deliver = () => client.onTicket(invocationId, ticketFor(transferId, { state: 'READY' }))
        return true
      },
      putObject: async () => {},
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    const promise = client.upload(INVOCATION_ID, binary, new AbortController().signal)
    await waitFor(() => sends === 1)
    client.onConnectionLost()
    await tick()
    client.onConnectionReady()
    // 重连后重放同一 transfer 的新控制帧到达。
    await waitFor(() => sends === 2)
    deliver?.()
    const result = await promise
    expect(result.uploadId).toBe(UPLOAD_ID)
  })

  it('re-submits a replayed PENDING ticket without re-uploading', async () => {
    const types: string[] = []
    let client: ResourceTransferClient
    let step = 0
    client = createResourceTransferClient({
      sendControl: (type, invocationId, payloadJson) => {
        types.push(type)
        const transferId = (JSON.parse(payloadJson) as { transferId: string }).transferId
        const state = step === 0 ? 'PENDING' : step === 1 ? 'PENDING' : 'READY'
        step++
        queueMicrotask(() => client.onTicket(invocationId, ticketFor(transferId, { state } as never)))
        return true
      },
      putObject: async () => {},
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    const result = await client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
    expect(types).toEqual(['RESOURCE_UPLOAD_REQUEST', 'RESOURCE_UPLOAD_COMMIT', 'RESOURCE_UPLOAD_COMMIT'])
  })

  it('falls back to re-uploading when storage rejects the PUT', async () => {
    let client: ResourceTransferClient
    let step = 0
    client = createResourceTransferClient({
      sendControl: (_type, invocationId, payloadJson) => {
        const transferId = (JSON.parse(payloadJson) as { transferId: string }).transferId
        const state = step === 0 ? 'PENDING' : 'READY'
        step++
        queueMicrotask(() => client.onTicket(invocationId, ticketFor(transferId, { state } as never)))
        return true
      },
      putObject: async () => {
        throw new Error('storage rejected')
      },
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    // PUT 失败后走 COMMIT 权威判定；READY 时直接成功。
    const result = await client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
  })
})

describe('presigned PUT http client', () => {
  function startServer(status: number): Promise<{ url: string; close: () => Promise<void>; seen: { method?: string; body: string } }> {
    const seen: { method?: string; body: string } = { body: '' }
    const server = createServer((request, response) => {
      seen.method = request.method
      request.on('data', (chunk) => {
        seen.body += chunk.toString('utf8')
      })
      request.on('end', () => {
        response.statusCode = status
        response.end('done')
      })
    })
    return new Promise((resolve) => {
      server.listen(0, '127.0.0.1', () => {
        const port = (server.address() as AddressInfo).port
        resolve({
          url: `http://127.0.0.1:${port}/upload`,
          seen,
          close: () => new Promise<void>((done) => server.close(() => done())),
        })
      })
    })
  }

  it('PUTs the bytes and resolves on success', async () => {
    const server = await startServer(200)
    try {
      const put = createHttpPutObject()
      await put(
        { method: 'PUT', url: server.url, headers: { 'content-type': 'application/octet-stream' }, expiresAt: '2030-01-01T00:00:00.000Z' },
        new Uint8Array([1, 2, 3]),
        new AbortController().signal,
      )
      expect(server.seen.method).toBe('PUT')
      expect([...Buffer.from(server.seen.body, 'utf8')]).toEqual([1, 2, 3])
    } finally {
      await server.close()
    }
  })

  it('reports only the storage status on failure', async () => {
    const server = await startServer(403)
    try {
      const put = createHttpPutObject()
      await expect(
        put(
          { method: 'PUT', url: server.url, headers: {}, expiresAt: '2030-01-01T00:00:00.000Z' },
          new Uint8Array([1]),
          new AbortController().signal,
        ),
      ).rejects.toThrow(/rejected by storage: 403/)
    } finally {
      await server.close()
    }
  })
})
