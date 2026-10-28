import {
  ApplicationEventConnection,
  type ApplicationEventSocketFactory,
} from '@/shared/app-events/connection'
import type {
  ApplicationEventConnectionIdentity,
  ApplicationEventIdeUse,
  ApplicationEventIdeUseView,
  ApplicationEventName,
  ApplicationEventResource,
  ApplicationEventRevision,
  ApplicationEventServerMessage,
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

export interface ApplicationEventManagerOptions {
  url: string
  /** 测试注入；生产使用原生 WebSocket。 */
  socketFactory?: ApplicationEventSocketFactory
}

/** ide-use 的异步确认；只回显服务端已接受的 revision。 */
export interface ApplicationEventIdeUseAck {
  revision: ApplicationEventRevision
}

interface SubscriptionEntry {
  resource: ApplicationEventResource
  /** listener -> refcount：同一 listener 重复 subscribe 也计真实引用。 */
  refs: Map<ApplicationEventListener, number>
}

interface PendingUsage {
  fingerprint: string
  request: ApplicationEventIdeUse
  promise: Promise<ApplicationEventIdeUseAck>
  resolve: (ack: ApplicationEventIdeUseAck) => void
  reject: (error: Error) => void
}

const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const CANONICAL_POSITIVE_DECIMAL = /^[1-9][0-9]*$/
const MAX_SIGNED_LONG_DECIMAL = '9223372036854775807'
const MAX_IDE_USE_VIEWS = 64

/**
 * 应用事件订阅管理：资源 listener/refcount、连接身份和 IDE 使用列表确认。
 * - 同一资源无论多少消费者都只有一条 wire 订阅：首 ref 发 subscribe（listener
 *   先登记再发送），末 ref 发 unsubscribe；同一 listener 重复 subscribe 有真实
 *   refcount，首个 unsubscribe 不拆 wire；
 * - 连接（重连）open 后重发所有 active subscriptions；connected 帧提供每次物理
 *   连接新的 eventNodeId/connectionId；
 * - subscribed/event/resync/error 按资源分发给 listeners；
 * - registerUsage 只在当前 open 且已收到 connected 时发送 ide-use，并以
 *   ide-use-accepted 确认；断线、协议错误或服务端拒绝会 reject，绝不在重连后自动
 *   重放该调用。
 */
export class ApplicationEventManager {
  private readonly connection: ApplicationEventConnection
  private readonly subscriptions = new Map<string, SubscriptionEntry>()
  private readonly identityListeners = new Set<
    (identity: ApplicationEventConnectionIdentity | null) => void
  >()
  private readonly pendingUsages = new Map<string, PendingUsage>()
  private connectionIdentity: ApplicationEventConnectionIdentity | null = null
  private acceptedDocumentId: string | null = null

  constructor(options: ApplicationEventManagerOptions) {
    this.connection = new ApplicationEventConnection({
      url: options.url,
      socketFactory: options.socketFactory,
      onOpen: () => this.handleOpen(),
      onMessage: (message) => this.dispatch(message),
      onClose: () => this.handleClose(),
    })
  }

  connect(): void {
    this.connection.connect()
  }

  disconnect(): void {
    this.connection.disconnect()
  }

  /** 当前 connected 帧确认的连接身份；尚未收到或连接已关闭时为 null。 */
  getIdentity(): ApplicationEventConnectionIdentity | null {
    return this.connectionIdentity
  }

  /** 订阅连接身份变化；立即回调当前值，返回取消函数。 */
  subscribeIdentity(
    listener: (identity: ApplicationEventConnectionIdentity | null) => void,
  ): () => void {
    this.identityListeners.add(listener)
    listener(this.connectionIdentity)
    return () => {
      this.identityListeners.delete(listener)
    }
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
   * 发送完整 IDE 使用列表并等待服务端 {@code ide-use-accepted}。
   *
   * <p>Promise 只在 ack 到达时 resolve（只回显 revision）；未连接/未收到 connected、连接关闭、
   * 服务端拒绝或同步发送失败都会 reject。同一 revision 相同内容的重复调用会重发同一 wire frame
   * 并复用 pending Promise，供回执丢失时在同一连接重试；不同内容立即 reject。断线不会自动重发。
   */
  registerUsage(usage: ApplicationEventIdeUse): Promise<ApplicationEventIdeUseAck> {
    let normalized: ApplicationEventIdeUse
    try {
      normalized = normalizeUsage(usage)
    } catch (error) {
      return Promise.reject(asError(error))
    }
    if (this.connectionIdentity == null || this.connection.getStatus() !== 'open') {
      return Promise.reject(new Error('application event connection is not ready'))
    }
    if (
      this.acceptedDocumentId != null
      && this.acceptedDocumentId !== normalized.documentId
    ) {
      return Promise.reject(new Error('documentId is fixed for this connection'))
    }
    const fingerprint = JSON.stringify(normalized)
    const existing = this.pendingUsages.get(normalized.revision)
    if (existing != null) {
      if (existing.fingerprint !== fingerprint) {
        return Promise.reject(
          new Error('ide-use revision is already pending with different content'),
        )
      }
      if (!this.sendUsage(normalized)) {
        this.pendingUsages.delete(normalized.revision)
        existing.reject(new Error('application event connection is not open'))
      }
      return existing.promise
    }

    let resolve!: (ack: ApplicationEventIdeUseAck) => void
    let reject!: (error: Error) => void
    const promise = new Promise<ApplicationEventIdeUseAck>((resolvePromise, rejectPromise) => {
      resolve = resolvePromise
      reject = rejectPromise
    })
    const pending: PendingUsage = {
      fingerprint,
      request: normalized,
      promise,
      resolve,
      reject,
    }
    // 先登记再发送：fake/同步 server ack 必须能找到该 pending。
    this.pendingUsages.set(normalized.revision, pending)
    if (!this.sendUsage(normalized)) {
      this.pendingUsages.delete(normalized.revision)
      pending.reject(new Error('application event connection is not open'))
    }
    return promise
  }

  private sendUsage(usage: ApplicationEventIdeUse): boolean {
    return this.connection.send({
      version: 2,
      type: 'ide-use',
      documentId: usage.documentId,
      revision: usage.revision,
      views: usage.views,
    })
  }

  private handleOpen(): void {
    this.setConnectionIdentity(null)
    this.acceptedDocumentId = null
    this.rejectPendingUsages('application event connection reopened')
    this.resubscribeAll()
  }

  private handleClose(): void {
    this.setConnectionIdentity(null)
    this.acceptedDocumentId = null
    this.rejectPendingUsages('application event connection closed')
  }

  private resubscribeAll(): void {
    for (const entry of this.subscriptions.values()) {
      this.connection.send({ version: 2, type: 'subscribe', resource: entry.resource })
    }
  }

  private dispatch(message: ApplicationEventServerMessage): void {
    if (message.type === 'connected') {
      this.setConnectionIdentity({
        eventNodeId: message.eventNodeId,
        connectionId: message.connectionId,
      })
      return
    }
    if (message.type === 'ide-use-accepted') {
      const pending = this.pendingUsages.get(message.revision)
      if (pending != null) {
        this.pendingUsages.delete(message.revision)
        this.acceptedDocumentId = pending.request.documentId
        pending.resolve({ revision: message.revision })
      }
      return
    }
    if (message.type === 'heartbeat') {
      return
    }
    if (message.type === 'error') {
      if (message.resource == null) {
        // ide-use 拒绝只有连接级 error；关联的未确认调用一律失败，由调用方决定新 revision。
        this.rejectPendingUsages(`${message.code}: ${message.message}`)
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

  private setConnectionIdentity(identity: ApplicationEventConnectionIdentity | null): void {
    if (
      this.connectionIdentity?.eventNodeId === identity?.eventNodeId
      && this.connectionIdentity?.connectionId === identity?.connectionId
    ) {
      return
    }
    this.connectionIdentity = identity
    for (const listener of [...this.identityListeners]) {
      try {
        listener(identity)
      } catch (error) {
        console.error('application event identity listener failed', error)
      }
    }
  }

  private rejectPendingUsages(message: string): void {
    if (this.pendingUsages.size === 0) {
      return
    }
    const pending = [...this.pendingUsages.values()]
    this.pendingUsages.clear()
    for (const usage of pending) {
      usage.reject(new Error(message))
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

/** 校验并规范化 ide-use：固定排序保证同 revision 的集合内容与顺序无关。 */
function normalizeUsage(usage: ApplicationEventIdeUse): ApplicationEventIdeUse {
  if (
    typeof usage.documentId !== 'string'
    || !CANONICAL_UUID.test(usage.documentId)
  ) {
    throw new Error('documentId must be a canonical UUID')
  }
  const revision = parseRevision(usage.revision)
  if (revision == null) {
    throw new Error('revision must be a canonical positive decimal within signed 64-bit range')
  }
  if (!Array.isArray(usage.views) || usage.views.length > MAX_IDE_USE_VIEWS) {
    throw new Error('views must be an array of at most 64 entries')
  }
  const viewIds = new Set<string>()
  const views = usage.views.map((view) => normalizeView(view, viewIds))
  views.sort(
    (left, right) =>
      left.environmentId.localeCompare(right.environmentId)
      || left.viewId.localeCompare(right.viewId),
  )
  return { documentId: usage.documentId, revision, views }
}

function normalizeView(
  view: ApplicationEventIdeUseView,
  viewIds: Set<string>,
): ApplicationEventIdeUseView {
  if (
    typeof view.environmentId !== 'string'
    || !CANONICAL_UUID.test(view.environmentId)
  ) {
    throw new Error('view.environmentId must be a canonical UUID')
  }
  if (typeof view.viewId !== 'string' || !CANONICAL_UUID.test(view.viewId)) {
    throw new Error('view.viewId must be a canonical UUID')
  }
  if (typeof view.claimId !== 'string' || !CANONICAL_UUID.test(view.claimId)) {
    throw new Error('view.claimId must be a canonical UUID')
  }
  if (viewIds.has(view.viewId)) {
    throw new Error('views must not repeat a viewId')
  }
  viewIds.add(view.viewId)
  if (view.bindingEpoch !== undefined) {
    if (parseRevision(view.bindingEpoch) == null) {
      throw new Error('bindingEpoch must be a canonical positive decimal within signed 64-bit range')
    }
    return {
      environmentId: view.environmentId,
      viewId: view.viewId,
      claimId: view.claimId,
      bindingEpoch: view.bindingEpoch,
    }
  }
  return {
    environmentId: view.environmentId,
    viewId: view.viewId,
    claimId: view.claimId,
  }
}

function parseRevision(value: unknown): ApplicationEventRevision | null {
  if (typeof value !== 'string' || !CANONICAL_POSITIVE_DECIMAL.test(value)) {
    return null
  }
  if (
    value.length > MAX_SIGNED_LONG_DECIMAL.length
    || (value.length === MAX_SIGNED_LONG_DECIMAL.length && value > MAX_SIGNED_LONG_DECIMAL)
  ) {
    return null
  }
  return value
}

function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}
