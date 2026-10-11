import { createHash, randomUUID } from 'node:crypto'

import type { ResourceUploader, ResultContent } from '../contracts.js'
import {
  decodeUploadTicket,
  encodeUploadCommit,
  encodeUploadRequest,
  type PresignedPut,
  type UploadRequest,
  type UploadTicket,
} from '../protocol.js'

/**
 * Daemon 侧 invocation 作用域资源上传客户端：把内存字节经控制面直传全局对象存储。
 *
 * <p>交互固定为 {@code RESOURCE_UPLOAD_REQUEST} → 票据 → 预签名 PUT → {@code RESOURCE_UPLOAD_COMMIT} →
 * 票据，直至 READY。传输以 {@code (invocationId, transferId)} 关联：同一 upload 调用内 transferId 恒定，重试与同
 * 实例重连只重放同一传输，绝不产生第二个上传行。
 *
 * <p>重试没有人为次数上限：唯一边界是被调用 invocation 的 AbortSignal（超时/取消/停机）与连接可用性。等待被中断时立即
 * 确定失败，绝不吞掉中断后继续等待。预签名 URL 与已签名 headers 只出现在实际 PUT 请求里，绝不进入错误文本。
 */

export type ResourceTransferMessageType = 'RESOURCE_UPLOAD_REQUEST' | 'RESOURCE_UPLOAD_COMMIT'

/** 出站控制帧递交端口；返回 false 表示连接当前不可用（帧确定未发送）。 */
export type ResourceControlSender = (
  messageType: ResourceTransferMessageType,
  invocationId: string,
  payloadJson: string,
) => boolean

export type PresignedPutSender = (presigned: PresignedPut, bytes: Uint8Array, signal: AbortSignal) => Promise<void>

export interface ResourceTransferClientOptions {
  sendControl: ResourceControlSender
  putObject: PresignedPutSender
  /** 单次控制面等待票据的超时上限。 */
  ticketWaitMillis?: number
  /** 可重试失败之间的退避间隔。 */
  retryBackoffMillis?: number
  /** 等待连接恢复时的轮询粒度。 */
  connectionPollMillis?: number
}

export interface ResourceTransferClient extends ResourceUploader {
  /** 接收一条服务端票据帧；(invocationId, transferId) 匹配时唤醒等待方，否则丢弃。 */
  onTicket(invocationId: string, payloadJson: string): void
  /** 连接失效：唤醒等待方进入重连等待。 */
  onConnectionLost(): void
  /** 连接已 READY：放行等待重连的上传继续重放同一 transfer。 */
  onConnectionReady(): void
  close(): void
}

const DEFAULT_TICKET_WAIT_MILLIS = 30_000
const DEFAULT_RETRY_BACKOFF_MILLIS = 200
const DEFAULT_CONNECTION_POLL_MILLIS = 200
const FAILURE_MESSAGE = 'resource upload failed'
const REJECTED_PREFIX = 'resource upload was rejected by storage'

interface PendingTicket {
  resolve(ticket: UploadTicket): void
  reject(error: Error): void
}

/** 生产预签名 PUT：只发送票据声明的已签名方法/headers，失败只报告状态码，绝不回显 URL 或签名。 */
export function createHttpPutObject(): PresignedPutSender {
  return async (presigned, bytes, signal) => {
    const response = await fetch(presigned.url, {
      method: presigned.method,
      headers: presigned.headers,
      body: bytes as unknown as BodyInit,
      signal,
    })
    if (!response.ok) {
      throw new Error(`${REJECTED_PREFIX}: ${response.status}`)
    }
  }
}

export function createResourceTransferClient(options: ResourceTransferClientOptions): ResourceTransferClient {
  return new DefaultResourceTransferClient(options)
}

class DefaultResourceTransferClient implements ResourceTransferClient {
  private readonly sendControl: ResourceControlSender
  private readonly putObject: PresignedPutSender
  private readonly ticketWaitMillis: number
  private readonly retryBackoffMillis: number
  private readonly connectionPollMillis: number
  private readonly pending = new Map<string, PendingTicket>()
  private connectionAvailable = false

  constructor(options: ResourceTransferClientOptions) {
    this.sendControl = options.sendControl
    this.putObject = options.putObject
    this.ticketWaitMillis = requirePositive(options.ticketWaitMillis ?? DEFAULT_TICKET_WAIT_MILLIS, 'ticketWaitMillis')
    this.retryBackoffMillis = requirePositive(options.retryBackoffMillis ?? DEFAULT_RETRY_BACKOFF_MILLIS, 'retryBackoffMillis')
    this.connectionPollMillis = requirePositive(options.connectionPollMillis ?? DEFAULT_CONNECTION_POLL_MILLIS, 'connectionPollMillis')
  }

  onTicket(invocationId: string, payloadJson: string): void {
    let ticket: UploadTicket
    try {
      ticket = decodeUploadTicket(payloadJson)
    } catch {
      // 非法票据是服务端的协议问题；客户端不因单条控制帧内容破坏已建立的连接。
      return
    }
    if (invocationId.trim() === '') {
      return
    }
    const pending = this.pending.get(transferKey(invocationId, ticket.transferId))
    if (pending !== undefined) {
      pending.resolve(ticket)
    }
  }

  onConnectionLost(): void {
    this.connectionAvailable = false
    const abandoned = [...this.pending.entries()]
    this.pending.clear()
    for (const [, pending] of abandoned) {
      pending.reject(new Error('connection lost'))
    }
  }

  onConnectionReady(): void {
    this.connectionAvailable = true
  }

  close(): void {
    this.onConnectionLost()
  }

  async upload(
    invocationId: string,
    content: Extract<ResultContent, { type: 'binary' }>,
    signal: AbortSignal,
  ): Promise<Extract<ResultContent, { type: 'resource' }>> {
    const bytes = content.bytes
    const request: UploadRequest = {
      transferId: randomUUID(),
      mediaType: content.mediaType,
      name: content.name ?? null,
      size: bytes.byteLength,
      sha256: sha256Hex(bytes),
    }
    const key = transferKey(invocationId, request.transferId)
    // uploaded 表示「本 transfer 的字节可能已经在对象存储中」：只有提交给出的权威失败结论才把它复位。
    let uploaded = false
    let uploadId: string | null = null
    try {
      for (;;) {
        this.throwIfAborted(signal)
        await this.awaitConnection(signal)
        let ticket: UploadTicket
        try {
          ticket = uploaded
            ? await this.control(
                invocationId,
                key,
                'RESOURCE_UPLOAD_COMMIT',
                encodeUploadCommit({ transferId: request.transferId, uploadId: uploadId as string }),
                signal,
              )
            : await this.control(invocationId, key, 'RESOURCE_UPLOAD_REQUEST', encodeUploadRequest(request), signal)
        } catch {
          await this.backoff(signal)
          continue
        }
        if (ticket.state === 'READY') {
          return {
            type: 'resource',
            uploadId: ticket.uploadId as string,
            mediaType: request.mediaType,
            name: request.name,
            size: request.size,
            sha256: request.sha256,
          }
        }
        if (ticket.state === 'FAILED') {
          // 提交失败说明对象确实不在存储中，回到上传阶段；申请失败则本来就没有上传过。
          uploaded = false
          uploadId = null
          await this.backoff(signal)
          continue
        }
        // PENDING
        if (uploaded) {
          // 服务端回放了尚未提交的申请票据：只重新提交，绝不重复上传字节。
          await this.backoff(signal)
        } else {
          uploadId = ticket.uploadId
          uploaded = true
          try {
            await this.putObject(ticket.presignedPut as PresignedPut, bytes, signal)
          } catch {
            // 对象存储拒绝或不确定字节是否落盘：下一次提交做权威判定，失败时再回到上传阶段。
            await this.backoff(signal)
          }
        }
      }
    } finally {
      this.pending.delete(key)
    }
  }

  private async control(
    invocationId: string,
    key: string,
    messageType: ResourceTransferMessageType,
    payloadJson: string,
    signal: AbortSignal,
  ): Promise<UploadTicket> {
    if (!this.connectionAvailable) {
      throw new Error('resource upload control is unavailable')
    }
    const promise = new Promise<UploadTicket>((resolve, reject) => {
      this.pending.set(key, { resolve, reject })
    })
    const onAbort = (): void => {
      const pending = this.pending.get(key)
      if (pending !== undefined) {
        this.pending.delete(key)
        pending.reject(new Error(FAILURE_MESSAGE))
      }
    }
    signal.addEventListener('abort', onAbort, { once: true })
    try {
      if (!this.sendControl(messageType, invocationId, payloadJson)) {
        throw new Error('resource upload control is unavailable')
      }
      return await withTimeout(promise, this.ticketWaitMillis, FAILURE_MESSAGE)
    } finally {
      signal.removeEventListener('abort', onAbort)
      this.pending.delete(key)
    }
  }

  private async awaitConnection(signal: AbortSignal): Promise<void> {
    while (!this.connectionAvailable && !signal.aborted) {
      await sleep(this.connectionPollMillis)
    }
    this.throwIfAborted(signal)
  }

  private async backoff(signal: AbortSignal): Promise<void> {
    this.throwIfAborted(signal)
    await sleep(this.retryBackoffMillis)
    this.throwIfAborted(signal)
  }

  private throwIfAborted(signal: AbortSignal): void {
    if (signal.aborted) {
      throw new Error(FAILURE_MESSAGE)
    }
  }
}

function transferKey(invocationId: string, transferId: string): string {
  return `${invocationId}\u0000${transferId}`
}

function sha256Hex(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex')
}

function withTimeout<T>(promise: Promise<T>, millis: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(message))
    }, Math.max(1, Math.min(millis, 2_147_483_647)))
    promise.then(
      (value) => {
        clearTimeout(timer)
        resolve(value)
      },
      (error) => {
        clearTimeout(timer)
        reject(error)
      },
    )
  })
}

function sleep(millis: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, Math.max(1, millis))
  })
}

function requirePositive(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer`)
  }
  return value
}
