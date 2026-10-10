import type {
  ApplicationEventClientMessage,
  ApplicationEventServerMessage,
} from '@/shared/app-events/protocol'
import {
  NotificationPacket,
  NotificationReassembler,
  carrierChunk,
  decodeNotificationCarrier,
  defaultNotificationLimits,
} from '@/shared/notification/notification.mjs'

/** 固定 server endpoint publisher：连接端借此冻结 peer 并校验 target。 */
export const SERVER_PUBLISHER = '5f0e2c1a-1111-4111-8111-0000000000ff'
const TOPIC = 'app.events.v2'
const LIMITS = defaultNotificationLimits()
const encoder = new TextEncoder()
const decoder = new TextDecoder('utf-8', { fatal: true })

/** 与生产一致的物理编码：逻辑 JSON → 共享 carrier 分片（count=1 也走同一路径）。 */
function encodeCarrier(message: ApplicationEventServerMessage, messageId: string): string {
  const body = JSON.stringify({ version: 2, ...message })
  const packet = new NotificationPacket(SERVER_PUBLISHER, null, TOPIC, messageId, encoder.encode(body))
  return carrierChunk(packet, 0).encode()
}

/** 用共享 reassembler 还原 fake 收到的物理分片为逻辑消息；不复制分片算法。 */
export function decodeSentMessages(frames: readonly string[]): ApplicationEventClientMessage[] {
  const delivered: ApplicationEventClientMessage[] = []
  // 观察端即 server peer：接受广播与 target=SERVER_PUBLISHER 的帧，且不丢弃本端（浏览器）publisher。
  const reassembler = new NotificationReassembler(
    SERVER_PUBLISHER,
    LIMITS,
    null,
    (packet) => {
      delivered.push(JSON.parse(decoder.decode(packet.bytes())) as ApplicationEventClientMessage)
    },
    () => undefined,
  )
  for (const raw of frames) {
    const carrier = decodeNotificationCarrier(raw, LIMITS, null)
    if (carrier != null) {
      reassembler.accept(carrier)
    }
  }
  return delivered
}

/**
 * WebSocket 测试替身：记录 url/发送的物理 carrier 分片，测试可驱动 open/fail/close
 * 与派发 server 帧。语义贴近浏览器：error 之后 close 是权威结束点；
 * 显式 close() 正常结束（code 1000），closeWith(code) 模拟服务端主动关闭。
 */
export class FakeWebSocket {
  readonly url: string
  /** 原始物理 carrier 分片（真实共享 carrier 编码，绝非 raw JSON）。 */
  readonly sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: ((event: { code: number }) => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  /** 模拟 native 发送缓冲；达到连接上界时 drain 推迟下一批。 */
  bufferedAmount = 0
  /** 为 true 时 send 同步抛错（模拟 open 与 send 之间的竞态/传输故障）。 */
  sendThrows = false
  /** 为 true 时 close 同步抛错（模拟浏览器对保留 close code/已关闭 socket 的拒绝）。 */
  closeThrows = false
  /** send() 时同步派发的 server 帧（测试「先登记 listener 再发 subscribe」等时序契约）。 */
  onSendResponse: ApplicationEventServerMessage | null = null
  private responseSeq = 0

  constructor(url: string) {
    this.url = url
  }

  send(data: string): void {
    if (this.sendThrows) {
      throw new Error('WebSocket is not open')
    }
    this.sent.push(data)
    if (this.onSendResponse != null) {
      this.responseSeq += 1
      const messageId = `ffffffff-ffff-4fff-8fff-${String(this.responseSeq).padStart(12, '0')}`
      this.onmessage?.({ data: encodeCarrier(this.onSendResponse, messageId) })
    }
  }

  open(): void {
    this.onopen?.()
  }

  /** 触发 error 并关闭（与浏览器一致：error 后必然 close；默认 1006 网络断线）。 */
  fail(): void {
    this.onerror?.()
    this.closeWith()
  }

  /** 以指定 close code 关闭（服务端主动 close，无 error 事件）。 */
  closeWith(code = 1006): void {
    if (this.closed) {
      return
    }
    this.closed = true
    this.onclose?.({ code })
  }

  /** 浏览器显式 close()（无参数）：正常关闭（code 1000）。 */
  close(): void {
    if (this.closeThrows) {
      throw new Error('WebSocket close failed')
    }
    this.closeWith(1000)
  }

  /** 以真实物理 carrier 帧派发 server 消息（连接端 codec 做完整严格校验）。 */
  emitServer(message: ApplicationEventServerMessage): void {
    this.responseSeq += 1
    const messageId = `ffffffff-ffff-4fff-8fff-${String(this.responseSeq).padStart(12, '0')}`
    this.onmessage?.({ data: encodeCarrier(message, messageId) })
  }

  /** 直接派发原始物理帧（用于验证 raw/binary/畸形 carrier 的收敛路径）。 */
  emitRaw(data: unknown): void {
    this.onmessage?.({ data })
  }

  /** 还原已发送分片为逻辑客户端消息（真实共享 carrier 解码/重组）。 */
  sentMessages(): ApplicationEventClientMessage[] {
    return decodeSentMessages(this.sent)
  }
}

/** 收集连接创建的所有 socket 并向连接注入 factory。 */
export class FakeWebSocketHarness {
  readonly sockets: FakeWebSocket[] = []
  readonly factory = (url: string): WebSocket => {
    const socket = new FakeWebSocket(url)
    this.sockets.push(socket)
    return socket as unknown as WebSocket
  }

  get latest(): FakeWebSocket | null {
    return this.sockets.at(-1) ?? null
  }

  /** 打开最新 socket（通常在挂载后立刻调用）。 */
  openLatest(): FakeWebSocket {
    const socket = this.latest
    if (socket == null) {
      throw new Error('no WebSocket created yet')
    }
    socket.open()
    return socket
  }
}
