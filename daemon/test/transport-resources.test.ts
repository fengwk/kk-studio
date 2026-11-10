import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { create } from '@bufbuild/protobuf'
import { Agent } from 'undici'

import {
  MessageType,
  PresignedPutSchema,
  ResourceUploadTicketSchema,
  TicketState,
  type Envelope,
  type PresignedPut,
  type ResourceUploadCommit,
  type ResourceUploadRequest,
  type ResourceUploadTicket,
} from '../src/generated/control_pb.js'
import {
  createHttpPutObject,
  createResourceTransferClient,
  type ResourceTransferClient,
} from '../src/transport/resources.js'
import { installOutboundNetwork, type InstalledOutboundNetwork } from '../src/network.js'
import { ResourceUploadError } from '../src/contracts.js'
import { tick, waitFor } from './helpers.js'

const INVOCATION_ID = 'inv-1'
const UPLOAD_ID = '99999999-8888-4777-8666-555555555555'

type TicketSpec = { state: 'PENDING' } | { state: 'READY' } | { state: 'FAILED'; message?: string }

function ticketFor(transferId: string, spec: TicketSpec): ResourceUploadTicket {
  switch (spec.state) {
    case 'PENDING':
      return create(ResourceUploadTicketSchema, {
        transferId,
        state: TicketState.TICKET_PENDING,
        uploadId: UPLOAD_ID,
        presignedPut: create(PresignedPutSchema, {
          method: 'PUT',
          url: 'https://s3.example.com/bucket/key',
          expiresAt: '2030-01-01T00:00:00.000Z',
        }),
      })
    case 'READY':
      return create(ResourceUploadTicketSchema, {
        transferId,
        state: TicketState.TICKET_READY,
        uploadId: UPLOAD_ID,
      })
    case 'FAILED':
      return create(ResourceUploadTicketSchema, {
        transferId,
        state: TicketState.TICKET_FAILED,
        message: spec.message ?? 'nope',
      })
  }
}

function getTransferId(envelope: Envelope): string {
  if (envelope.messageType === MessageType.RESOURCE_UPLOAD_REQUEST) {
    return (envelope.payload.value as ResourceUploadRequest).transferId ?? ''
  }
  if (envelope.messageType === MessageType.RESOURCE_UPLOAD_COMMIT) {
    return (envelope.payload.value as ResourceUploadCommit).transferId ?? ''
  }
  return ''
}

interface Script {
  client: ResourceTransferClient
  requests: { type: MessageType; invocationId: string; envelope: Envelope }[]
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
    sendControl: (envelope: Envelope) => {
      requests.push({ type: envelope.messageType, invocationId: envelope.invocationId ?? '', envelope })
      const transferId = getTransferId(envelope)
      const spec = script.shift()
      if (spec !== undefined) {
        queueMicrotask(() => client.onTicket(envelope.invocationId ?? '', ticketFor(transferId, spec)))
      }
      return true
    },
    putObject: async (_presigned: PresignedPut, bytes: Uint8Array) => {
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
    expect(script.requests.map((request) => request.type)).toEqual([
      MessageType.RESOURCE_UPLOAD_REQUEST,
      MessageType.RESOURCE_UPLOAD_COMMIT,
    ])
    const request = script.requests[0]?.envelope.payload.value as ResourceUploadRequest
    expect(request.size).toBe(4n)
    const commit = script.requests[1]?.envelope.payload.value as ResourceUploadCommit
    expect(commit.uploadId).toBe(UPLOAD_ID)
    expect(commit.transferId).toBe(request.transferId)
  })

  it('retries with the same transferId while waiting for a ticket', async () => {
    // 票据等待超时属于连接事实：同一 transfer 重试，绝不产生第二个上传行。
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'READY' }])
    const result = await script.client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
    expect(script.putCalls).toBe(0)
    const requests = script.requests.map((request) => request.envelope.payload.value as ResourceUploadRequest)
    expect(requests).toHaveLength(1)
    expect(requests[0]?.transferId).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('ends the transfer immediately on a TICKET_FAILED commit instead of retrying the ticket', async () => {
    // TICKET_FAILED 是本 transfer 的失败结论：立即结束，不再退避重试同一张票据掩盖错误。
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'PENDING' }, { state: 'FAILED' }, { state: 'PENDING' }])
    const controller = new AbortController()
    const promise = script.client.upload(INVOCATION_ID, binary, controller.signal)
    await expect(promise).rejects.toMatchObject({
      name: 'ResourceUploadError',
      stage: 'commit',
      category: 'ticket-failed',
      cleanupConfirmed: true,
    })
    // 失败结论之后绝不再触发第三次 REQUEST/COMMIT；abort 兜底只是清理守卫。
    expect(script.requests).toHaveLength(2)
    controller.abort()
  })

  it('ends the transfer immediately on a TICKET_FAILED request without uploading', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'FAILED' }])
    const controller = new AbortController()
    await expect(script.client.upload(INVOCATION_ID, binary, controller.signal)).rejects.toMatchObject({
      stage: 'request',
      category: 'ticket-failed',
      cleanupConfirmed: true,
    })
    expect(script.requests).toHaveLength(1)
    expect(script.putCalls).toBe(0)
    controller.abort()
  })

  it('does not echo ticket URLs or headers in a TICKET_FAILED failure', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'FAILED', message: 'storage quota exhausted' }])
    const controller = new AbortController()
    const error = await script.client.upload(INVOCATION_ID, binary, controller.signal).then(
      () => undefined,
      (reason: unknown) => reason,
    )
    controller.abort()
    // 诊断链只含安全文案：错误本体是固定分类文案，cause 是平台给出的有界票据 message；
    // 预签名 URL 与签名 headers 绝不进入任何一层。
    expect(error).toBeInstanceOf(ResourceUploadError)
    const typed = error as ResourceUploadError
    expect(typed.message).not.toContain('s3.example.com')
    expect(typed.cause).toBeInstanceOf(Error)
    expect((typed.cause as Error).message).toBe('storage quota exhausted')
    expect(JSON.stringify(typed.cause)).not.toContain('s3.example.com')
  })

  it('rejects when the invocation signal is already aborted', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    const controller = new AbortController()
    controller.abort('cancelled')
    await expect(script.client.upload(INVOCATION_ID, binary, controller.signal)).rejects.toMatchObject({
      name: 'ResourceUploadError',
      category: 'cancelled',
      cleanupConfirmed: true,
    })
  })

  it('waits for reconnect and resumes the same transfer', async () => {
    const requests: { type: MessageType; envelope: Envelope }[] = []
    let deliver: (() => void) | null = null
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (envelope: Envelope) => {
        requests.push({ type: envelope.messageType, envelope })
        const transferId = getTransferId(envelope)
        deliver = () => client.onTicket(envelope.invocationId ?? '', ticketFor(transferId, { state: 'READY' }))
        return true
      },
      putObject: async () => {},
      ticketWaitMillis: 2000,
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    const promise = client.upload(INVOCATION_ID, binary, new AbortController().signal)
    await tick()
    expect(requests.length).toBe(0)
    client.onConnectionReady()
    await waitFor(() => deliver !== null)
    deliver?.()
    const result = await promise
    expect(result.uploadId).toBe(UPLOAD_ID)
  })

  it('ignores unmatched tickets', async () => {
    const script = makeClient()
    script.client.onConnectionReady()
    script.setScript([{ state: 'READY' }])
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
    const requests: MessageType[] = []
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (envelope: Envelope) => {
        attempts++
        if (attempts === 1) {
          return false
        }
        requests.push(envelope.messageType)
        const transferId = getTransferId(envelope)
        queueMicrotask(() => client.onTicket(envelope.invocationId ?? '', ticketFor(transferId, { state: 'READY' })))
        return true
      },
      putObject: async () => {},
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    const result = await client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
    expect(requests).toEqual([MessageType.RESOURCE_UPLOAD_REQUEST])
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
    await expect(promise).rejects.toMatchObject({ category: 'cancelled', cleanupConfirmed: true })
  })

  it('times out a ticket wait and retries under the same transfer', async () => {
    const transferIds: string[] = []
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (envelope: Envelope) => {
        transferIds.push(getTransferId(envelope))
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
    await expect(promise).rejects.toMatchObject({ category: 'cancelled', cleanupConfirmed: true })
    expect(new Set(transferIds).size).toBe(1)
  })

  it('waits for reconnect after a lost connection and resumes the same transfer', async () => {
    let sends = 0
    let deliver: (() => void) | null = null
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (envelope: Envelope) => {
        sends++
        const transferId = getTransferId(envelope)
        deliver = () => client.onTicket(envelope.invocationId ?? '', ticketFor(transferId, { state: 'READY' }))
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
    await waitFor(() => sends === 2)
    deliver?.()
    const result = await promise
    expect(result.uploadId).toBe(UPLOAD_ID)
  })

  it('re-submits a replayed PENDING ticket without re-uploading', async () => {
    const types: MessageType[] = []
    let client: ResourceTransferClient
    let step = 0
    client = createResourceTransferClient({
      sendControl: (envelope: Envelope) => {
        types.push(envelope.messageType)
        const transferId = getTransferId(envelope)
        const state = step === 0 ? 'PENDING' : step === 1 ? 'PENDING' : 'READY'
        step++
        queueMicrotask(() => client.onTicket(envelope.invocationId ?? '', ticketFor(transferId, { state } as never)))
        return true
      },
      putObject: async () => {},
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    const result = await client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
    expect(types).toEqual([
      MessageType.RESOURCE_UPLOAD_REQUEST,
      MessageType.RESOURCE_UPLOAD_COMMIT,
      MessageType.RESOURCE_UPLOAD_COMMIT,
    ])
  })

  it('falls back to re-uploading when storage rejects the PUT', async () => {
    let client: ResourceTransferClient
    let step = 0
    client = createResourceTransferClient({
      sendControl: (envelope: Envelope) => {
        const transferId = getTransferId(envelope)
        const state = step === 0 ? 'PENDING' : 'READY'
        step++
        queueMicrotask(() => client.onTicket(envelope.invocationId ?? '', ticketFor(transferId, { state } as never)))
        return true
      },
      putObject: async () => {
        throw new Error('storage rejected')
      },
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    const result = await client.upload(INVOCATION_ID, binary, new AbortController().signal)
    expect(result.uploadId).toBe(UPLOAD_ID)
  })
})

describe('presigned PUT http client', () => {
  let network: InstalledOutboundNetwork
  beforeEach(async () => {
    network = await installOutboundNetwork()
  })
  afterEach(async () => {
    await network.close()
  })

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
        create(PresignedPutSchema, {
          method: 'PUT',
          url: server.url,
          expiresAt: '2030-01-01T00:00:00.000Z',
        }),
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
          create(PresignedPutSchema, {
            method: 'PUT',
            url: server.url,
            expiresAt: '2030-01-01T00:00:00.000Z',
          }),
          new Uint8Array([1]),
          new AbortController().signal,
        ),
      ).rejects.toThrow(/rejected by storage: 403/)
    } finally {
      await server.close()
    }
  })

  it('ends the transfer with exit unknown when the PUT network cannot be released', async () => {
    // PUT 成功但其独占 dispatcher 关闭失败：网络范围未证明释放，交付必须以 cleanupConfirmed=false 结束，
    // 不能继续 COMMIT 让退出被确认。
    const server = await startServer(200)
    const failure = new Error('dispatcher close failed')
    const spy = vi.spyOn(Agent.prototype, 'close').mockImplementation(() => Promise.reject(failure))
    const sent: MessageType[] = []
    let client: ResourceTransferClient
    client = createResourceTransferClient({
      sendControl: (envelope: Envelope) => {
        sent.push(envelope.messageType)
        const ticket = create(ResourceUploadTicketSchema, {
          transferId: getTransferId(envelope),
          state: TicketState.TICKET_PENDING,
          uploadId: UPLOAD_ID,
          presignedPut: create(PresignedPutSchema, {
            method: 'PUT',
            url: server.url,
            expiresAt: '2030-01-01T00:00:00.000Z',
          }),
        })
        queueMicrotask(() => client.onTicket(envelope.invocationId ?? '', ticket))
        return true
      },
      putObject: createHttpPutObject(),
      retryBackoffMillis: 5,
      connectionPollMillis: 5,
    })
    client.onConnectionReady()
    try {
      await expect(client.upload(INVOCATION_ID, binary, new AbortController().signal)).rejects.toMatchObject({
        stage: 'put',
        category: 'cleanup-failed',
        cleanupConfirmed: false,
      })
      expect(sent).toEqual([MessageType.RESOURCE_UPLOAD_REQUEST])
    } finally {
      spy.mockRestore()
      await server.close()
    }
  })
})
