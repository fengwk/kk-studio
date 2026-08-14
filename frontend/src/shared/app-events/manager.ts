import {
  ApplicationEventConnection,
  type ApplicationEventSocketFactory,
} from '@/shared/app-events/connection'
import type {
  ApplicationEventName,
  ApplicationEventResource,
  ApplicationEventServerMessage,
} from '@/shared/app-events/protocol'

export interface ApplicationEventListener {
  /** wire 订阅建立（首次与每次重连后都会触发）：调用方在此与权威快照对账。 */
  onSubscribed?: () => void
  /** 资源事件：thread 为 revision/realtime，canvas 为 version。 */
  onEvent?: (name: ApplicationEventName, data: unknown) => void
  /** 服务端要求整体替换为全量快照。 */
  onResync?: () => void
  /** 服务端报告该资源订阅/事件处理失败。 */
  onError?: (message: string | undefined) => void
}

export interface ApplicationEventManagerOptions {
  url: string
  /** 测试注入；生产使用原生 WebSocket。 */
  socketFactory?: ApplicationEventSocketFactory
}

interface SubscriptionEntry {
  resource: ApplicationEventResource
  listeners: Set<ApplicationEventListener>
}

/**
 * 应用事件订阅管理：资源 listener/refcount。
 * - 同一资源无论多少消费者都只有一条 wire 订阅：首 listener 发 subscribe，
 *   末 listener 发 unsubscribe；
 * - 连接（重连）open 后重发所有 active subscriptions；
 * - subscribed/event/resync/error 按资源分发给 listeners。
 */
export class ApplicationEventManager {
  private readonly connection: ApplicationEventConnection
  private readonly subscriptions = new Map<string, SubscriptionEntry>()

  constructor(options: ApplicationEventManagerOptions) {
    this.connection = new ApplicationEventConnection({
      url: options.url,
      socketFactory: options.socketFactory,
      onOpen: () => this.resubscribeAll(),
      onMessage: (message) => this.dispatch(message),
    })
  }

  connect(): void {
    this.connection.connect()
  }

  disconnect(): void {
    this.connection.disconnect()
  }

  /** 注册资源 listener；返回取消函数（幂等）。 */
  subscribe(
    resource: ApplicationEventResource,
    listener: ApplicationEventListener,
  ): () => void {
    const key = resourceKey(resource)
    let entry = this.subscriptions.get(key)
    if (entry == null) {
      entry = { resource, listeners: new Set() }
      this.subscriptions.set(key, entry)
      this.connection.send({ type: 'subscribe', resource })
    }
    entry.listeners.add(listener)
    return () => {
      const current = this.subscriptions.get(key)
      if (current == null) {
        return
      }
      current.listeners.delete(listener)
      if (current.listeners.size === 0) {
        this.subscriptions.delete(key)
        this.connection.send({ type: 'unsubscribe', resource })
      }
    }
  }

  private resubscribeAll(): void {
    for (const entry of this.subscriptions.values()) {
      this.connection.send({ type: 'subscribe', resource: entry.resource })
    }
  }

  private dispatch(message: ApplicationEventServerMessage): void {
    if (message.type === 'error') {
      if (message.resource == null) {
        return
      }
      const entry = this.subscriptions.get(resourceKey(message.resource))
      if (entry == null) {
        return
      }
      for (const listener of [...entry.listeners]) {
        listener.onError?.(message.message)
      }
      return
    }
    const entry = this.subscriptions.get(resourceKey(message.resource))
    if (entry == null) {
      return
    }
    for (const listener of [...entry.listeners]) {
      if (message.type === 'subscribed') {
        listener.onSubscribed?.()
      } else if (message.type === 'event') {
        listener.onEvent?.(message.name, message.data)
      } else {
        listener.onResync?.()
      }
    }
  }
}

function resourceKey(resource: ApplicationEventResource): string {
  return `${resource.kind}:${resource.id}`
}
