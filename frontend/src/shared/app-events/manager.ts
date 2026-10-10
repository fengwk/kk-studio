import {
  ApplicationEventConnection,
  type ApplicationEventConnectionStatus,
  type ApplicationEventSocketFactory,
} from '@/shared/app-events/connection'
import type {
  ApplicationEventName,
  ApplicationEventResource,
  ApplicationEventServerMessage,
  TerminalCommand,
  TerminalEvent,
} from '@/shared/app-events/protocol'

export interface ApplicationEventListener {
  /** wire 订阅建立（首次与每次重连后都会触发）；cursor 是资源当前游标。 */
  onSubscribed?: (cursor: string) => void
  /** 资源事件：thread 为 version/realtime，canvas 为 version，projects 为 changed。 */
  onEvent?: (name: ApplicationEventName, data: unknown, cursor: string | undefined) => void
  /** 服务端要求整体替换为全量快照。 */
  onResync?: () => void
  /** 服务端报告该资源订阅/事件处理失败。 */
  onError?: (code: string, message: string) => void
}

/** 终端 shell 事件/连接状态 listener；只按 viewerId 分发，不建立 resource 订阅。 */
export interface ApplicationEventTerminalListener {
  /** 该 viewer 的 shell.event（TerminalControlCodec 严格解码后的 owned 事件）。 */
  onEvent?: (event: TerminalEvent) => void
  /** 连接状态实际变更时通知；登记时立即以当前状态通知一次。 */
  onStatusChange?: (status: ApplicationEventConnectionStatus) => void
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
 * 应用事件订阅管理：资源 listener/refcount 与终端 shell listener。
 * - 同一资源无论多少消费者都只有一条 wire 订阅：首 ref 发 subscribe（listener
 *   先登记再发送），末 ref 发 unsubscribe；同一 listener 重复 subscribe 有真实
 *   refcount，首个 unsubscribe 不拆 wire；
 * - 连接（重连）open 后重发所有 active subscriptions；
 * - subscribed/event/resync/error 按资源分发给 listeners；
 * - shell.event 仅按 viewerId 分发给该页面终端 listener；sendTerminal 复用同一连接，
 *   不保存 route/lease、不自动重放 INPUT/OPEN。
 */
export class ApplicationEventManager {
  private readonly connection: ApplicationEventConnection
  private readonly subscriptions = new Map<string, SubscriptionEntry>()
  private readonly terminalSubscriptions = new Map<
    string,
    Map<ApplicationEventTerminalListener, number>
  >()

  constructor(options: ApplicationEventManagerOptions) {
    this.connection = new ApplicationEventConnection({
      url: options.url,
      socketFactory: options.socketFactory,
      onOpen: () => this.resubscribeAll(),
      onMessage: (message) => this.dispatch(message),
      onStatusChange: (status) => this.notifyTerminalStatus(status),
    })
  }

  connect(): void {
    this.connection.connect()
  }

  disconnect(): void {
    this.connection.disconnect()
  }

  getStatus(): ApplicationEventConnectionStatus {
    return this.connection.getStatus()
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
      this.connection.send({ version: 2, type: 'subscribe', resource })
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
        this.connection.send({ version: 2, type: 'unsubscribe', resource })
      }
    }
  }

  /**
   * 注册某 viewer 的终端 listener；登记时立即通知当前连接状态。
   * 返回幂等取消函数（同 listener 重复注册计真实 refcount）。
   */
  subscribeTerminal(
    viewerId: string,
    listener: ApplicationEventTerminalListener,
  ): () => void {
    let entry = this.terminalSubscriptions.get(viewerId)
    if (entry == null) {
      entry = new Map()
      this.terminalSubscriptions.set(viewerId, entry)
    }
    entry.set(listener, (entry.get(listener) ?? 0) + 1)
    listener.onStatusChange?.(this.connection.getStatus())
    return () => {
      const current = this.terminalSubscriptions.get(viewerId)
      if (current == null) {
        return
      }
      const count = current.get(listener)
      if (count == null) {
        return
      }
      if (count > 1) {
        current.set(listener, count - 1)
        return
      }
      current.delete(listener)
      if (current.size === 0) {
        this.terminalSubscriptions.delete(viewerId)
      }
    }
  }

  /** 通过唯一连接发送 shell.command；非法命令或未连接返回 false（绝不抛错到 UI）。 */
  sendTerminal(command: TerminalCommand): boolean {
    return this.connection.send({ version: 2, type: 'shell.command', command })
  }

  private resubscribeAll(): void {
    for (const entry of this.subscriptions.values()) {
      this.connection.send({ version: 2, type: 'subscribe', resource: entry.resource })
    }
  }

  private dispatch(message: ApplicationEventServerMessage): void {
    if (message.type === 'heartbeat') {
      return
    }
    if (message.type === 'shell.event') {
      this.dispatchTerminal(message.event)
      return
    }
    if (message.type === 'error') {
      if (message.resource == null) {
        return
      }
      const entry = this.subscriptions.get(resourceKey(message.resource))
      if (entry == null) {
        return
      }
      this.notifyListeners(entry, (listener) => {
        listener.onError?.(message.code, message.message)
      })
      return
    }
    const entry = this.subscriptions.get(resourceKey(message.resource))
    if (entry == null) {
      return
    }
    this.notifyListeners(entry, (listener) => {
      if (message.type === 'subscribed') {
        listener.onSubscribed?.(message.cursor)
      } else if (message.type === 'event') {
        listener.onEvent?.(message.name, message.data, 'cursor' in message ? message.cursor : undefined)
      } else {
        listener.onResync?.()
      }
    })
  }

  /** shell.event 只按 viewerId 分发；没有该 viewer listener 时静默丢弃。 */
  private dispatchTerminal(event: TerminalEvent): void {
    const entry = this.terminalSubscriptions.get(event.viewerId)
    if (entry == null) {
      return
    }
    for (const listener of [...entry.keys()]) {
      try {
        listener.onEvent?.(event)
      } catch {
        // 固定去敏信息：绝不把 listener 异常对象（可能含 WriterGrant/输入数据）写入日志。
        console.error('application event terminal listener failed')
      }
    }
  }

  /** 连接状态变更广播给全部终端 listener；单个 listener 抛错被隔离。 */
  private notifyTerminalStatus(status: ApplicationEventConnectionStatus): void {
    for (const entry of this.terminalSubscriptions.values()) {
      for (const listener of [...entry.keys()]) {
        try {
          listener.onStatusChange?.(status)
        } catch {
          // 固定去敏信息：绝不把 listener 异常对象写入日志。
          console.error('application event terminal status listener failed')
        }
      }
    }
  }

  /** 快照迭代；单个 listener 抛错只隔离该消费者，不阻断同资源其他 listener。 */
  private notifyListeners(
    entry: SubscriptionEntry,
    notify: (listener: ApplicationEventListener) => void,
  ): void {
    for (const listener of [...entry.refs.keys()]) {
      try {
        notify(listener)
      } catch (error) {
        console.error('application event listener failed', error)
      }
    }
  }
}

function resourceKey(resource: ApplicationEventResource): string {
  return 'id' in resource ? `${resource.kind}:${resource.id}` : resource.kind
}
