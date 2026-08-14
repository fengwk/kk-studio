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
  /** wire 订阅建立（首次与每次重连后都会触发）；cursor 是资源当前游标。 */
  onSubscribed?: (cursor: string) => void
  /** 资源事件：thread 为 revision/realtime，canvas 为 version；durable（revision/version）必带 cursor，realtime 不带。 */
  onEvent?: (name: ApplicationEventName, data: unknown, cursor: string | undefined) => void
  /** 服务端要求整体替换为全量快照。 */
  onResync?: () => void
  /** 服务端报告该资源订阅/事件处理失败。 */
  onError?: (code: string, message: string) => void
}

export interface ApplicationEventManagerOptions {
  url: string
  /** 测试注入；生产使用原生 WebSocket。 */
  socketFactory?: ApplicationEventSocketFactory
}

interface SubscriptionEntry {
  resource: ApplicationEventResource
  /** listener -> refcount：同一 listener 重复 subscribe 也计真实引用。 */
  refs: Map<ApplicationEventListener, number>
}

/**
 * 应用事件订阅管理：资源 listener/refcount。
 * - 同一资源无论多少消费者都只有一条 wire 订阅：首 ref 发 subscribe（listener
 *   先登记再发送），末 ref 发 unsubscribe；同一 listener 重复 subscribe 有真实
 *   refcount，首个 unsubscribe 不拆 wire；
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

  /** 注册资源 listener；返回取消函数（幂等；同 listener 重复注册计真实 refcount）。 */
  subscribe(
    resource: ApplicationEventResource,
    listener: ApplicationEventListener,
  ): () => void {
    const key = resourceKey(resource)
    let entry = this.subscriptions.get(key)
    if (entry == null) {
      entry = { resource, refs: new Map() }
      this.subscriptions.set(key, entry)
    }
    const previous = entry.refs.get(listener) ?? 0
    entry.refs.set(listener, previous + 1)
    if (previous === 0 && entry.refs.size === 1) {
      // 首 ref：listener 已登记，此时才发首 subscribe（wire 响应可同步到达）。
      this.connection.send({ version: 1, type: 'subscribe', resource })
    }
    return () => {
      const current = this.subscriptions.get(key)
      if (current == null) {
        return
      }
      const count = current.refs.get(listener)
      if (count == null) {
        return // 幂等：该 listener 已完全释放。
      }
      if (count > 1) {
        current.refs.set(listener, count - 1)
        return
      }
      current.refs.delete(listener)
      if (current.refs.size === 0) {
        this.subscriptions.delete(key)
        this.connection.send({ version: 1, type: 'unsubscribe', resource })
      }
    }
  }

  private resubscribeAll(): void {
    for (const entry of this.subscriptions.values()) {
      this.connection.send({ version: 1, type: 'subscribe', resource: entry.resource })
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
      for (const listener of [...entry.refs.keys()]) {
        listener.onError?.(message.code, message.message)
      }
      return
    }
    const entry = this.subscriptions.get(resourceKey(message.resource))
    if (entry == null) {
      return
    }
    for (const listener of [...entry.refs.keys()]) {
      if (message.type === 'subscribed') {
        listener.onSubscribed?.(message.cursor)
      } else if (message.type === 'event') {
        listener.onEvent?.(message.name, message.data, 'cursor' in message ? message.cursor : undefined)
      } else {
        listener.onResync?.()
      }
    }
  }
}

function resourceKey(resource: ApplicationEventResource): string {
  return `${resource.kind}:${resource.id}`
}
