import { ApiError } from '@/shared/api/client'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasCommandDTO,
  CanvasConflictDTO,
  CanvasPatchDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import {
  getCanvas,
  postCanvasCommands,
} from '@/shared/api/studio-service'
import { compareCanvasRevisions } from '@/shared/lib/canvas-version'
import { getCurrentUserId } from '@/features/canvas/canvas-local-store'
import { getEditingSessionId } from '@/features/canvas/canvas-editing-session'
import {
  buildPendingOperation,
  createCanvasOperationStore,
  type CanvasDraftAck,
  type CanvasPendingOperation,
  type CanvasPendingOperationStore,
} from '@/features/canvas/canvas-operation-storage'
import { applyEntityPatch } from '@/features/canvas/entity-patch'

export class CanvasCommandConflictError extends Error {
  readonly snapshot: CanvasSnapshotDTO
  readonly cause: ApiError
  readonly conflicts: CanvasConflictDTO[]

  constructor(snapshot: CanvasSnapshotDTO, cause: ApiError) {
    super('Canvas changed on the server. The latest snapshot was loaded; review and retry your action.')
    this.name = 'CanvasCommandConflictError'
    this.snapshot = snapshot
    this.cause = cause
    this.conflicts = Array.isArray(cause.errors?.conflicts)
      ? (cause.errors.conflicts as CanvasConflictDTO[])
      : []
  }
}

export class CanvasQueueBlockedError extends Error {
  readonly retryable = true
  readonly cause?: unknown

  constructor(
    message = 'Canvas command queue is blocked by an earlier unconfirmed operation; recover and retry.',
    cause?: unknown,
  ) {
    super(message)
    this.name = 'CanvasQueueBlockedError'
    this.cause = cause
  }
}

export interface CanvasCommandQueueOptions {
  initialSnapshot: CanvasSnapshotDTO
  apply?: typeof postCanvasCommands
  refetch?: typeof getCanvas
  createCommandId?: () => UUIDString
  onSnapshot?: (snapshot: CanvasSnapshotDTO) => void
  /** 冻结操作的持久化存储；未提供时按 canvasId + 编辑会话使用 IndexedDB operation store。 */
  operationStore?: CanvasPendingOperationStore
  userId?: string
  editingSessionId?: string
}

/** 重载后按冻结顺序重放待确认操作的结果统计。 */
export interface CanvasCommandRecoveryResult {
  replayed: number
  conflicted: number
  failed: number
  /** 重放成功且需要按 generation 精准清除的草稿范围 */
  ackedDrafts: CanvasDraftAck[]
}

/**
 * Canvas graph 变更按浏览器顺序串行化。
 *
 * durability 契约（docs/canvas-project.md §2.4）：
 * - 入队瞬间即深拷贝并冻结命令体、idempotencyKey 与编辑基线 revision；
 * - 发送前先把冻结操作写入独立 operation store，且等待事务 complete；
 * - 网络 / 瞬时失败保留冻结操作，重载后按原顺序、原 key、原 body 精确重放（服务端按 key 去重）；
 * - 语义 409 是确定终态：绝不盲重放，保留草稿交由 UI 明确解决后生成新 key；
 * - ACK 只清除与本次 operation 完全匹配的持久记录，旧响应不会清掉后来的输入；
 * - 响应 revision 与本地快照存在缺口时，绝不假装增量 patch 完整，必须重取权威快照。
 */
export class CanvasCommandQueue {
  private snapshot: CanvasSnapshotDTO
  private tail: Promise<void> = Promise.resolve()
  private sequence = 0
  private activeRecovery: Promise<CanvasCommandRecoveryResult> | null = null
  private blocked = false
  private blockedError: Error | null = null
  private readonly operations = new Map<string, CanvasPendingOperation>()
  private readonly apply: typeof postCanvasCommands
  private readonly refetch: typeof getCanvas
  private readonly createCommandId: () => UUIDString
  private readonly onSnapshot?: (snapshot: CanvasSnapshotDTO) => void
  private readonly store: CanvasPendingOperationStore
  private readonly userId: string
  private readonly editingSessionId: string

  constructor(
    private readonly canvasId: UUIDString,
    options: CanvasCommandQueueOptions,
  ) {
    this.snapshot = options.initialSnapshot
    this.apply = options.apply ?? postCanvasCommands
    this.refetch = options.refetch ?? getCanvas
    this.createCommandId = options.createCommandId ?? (() => crypto.randomUUID())
    this.onSnapshot = options.onSnapshot
    this.userId = options.userId ?? getCurrentUserId()
    this.editingSessionId = options.editingSessionId ?? getEditingSessionId()
    this.store = options.operationStore ?? createCanvasOperationStore(canvasId, {
      userId: this.userId,
      editingSessionId: this.editingSessionId,
    })
  }

  isBlocked(): boolean {
    return this.blocked
  }

  getBlockedError(): Error | null {
    return this.blockedError
  }

  currentSnapshot(): CanvasSnapshotDTO {
    return this.snapshot
  }

  replaceSnapshot(snapshot: CanvasSnapshotDTO): CanvasSnapshotDTO {
    if (
      snapshot.document.id === this.snapshot.document.id
      && compareCanvasRevisions(snapshot.document.revision, this.snapshot.document.revision) < 0
    ) {
      return this.snapshot
    }
    this.adopt(snapshot)
    return snapshot
  }

  /**
   * 入队一个命令批：立即冻结 id / body / 基线，发送前先落盘，再按序提交。
   * `ack` 描述本操作成功后需要按 generation 精准清除的草稿范围。
   */
  enqueue(
    commands: ApplyCanvasCommandsRequestDTO['commands'],
    options?: { signal?: AbortSignal; ack?: CanvasDraftAck[] },
  ): Promise<CanvasSnapshotDTO> {
    if (this.blocked) {
      return Promise.reject(new CanvasQueueBlockedError(undefined, this.blockedError))
    }
    if (commands.length === 0) {
      return Promise.resolve(this.snapshot)
    }
    const operation = this.freeze(commands, options?.ack ?? [])
    this.operations.set(operation.id, operation)
    const result = this.tail.then(() => this.persistAndSend(operation, options?.signal))
    this.tail = result.then(
      () => undefined,
      () => undefined,
    )
    return result
  }

  /**
   * 重载 / 重连后按冻结顺序重放本会话残留的待确认操作。
   * recover 每次可再次调用（仅并发时 singleflight，不永久缓存首次失败），按冻结排序原 key 原 body 发送。
   * 首个未知结果即停，明确 409 / 确定 4xx 可 settle 并继续。
   */
  recover(): Promise<CanvasCommandRecoveryResult> {
    if (this.activeRecovery) {
      return this.activeRecovery
    }
    const run = this.tail.then(() => this.runRecovery())
    this.tail = run.then(
      () => undefined,
      () => undefined,
    )
    const active = run.finally(() => {
      if (this.activeRecovery === active) {
        this.activeRecovery = null
      }
    })
    this.activeRecovery = active
    return this.activeRecovery
  }

  private freeze(commands: CanvasCommandDTO[], ack: CanvasDraftAck[]): CanvasPendingOperation {
    return buildPendingOperation({
      canvasId: this.canvasId,
      userId: this.userId,
      editingSessionId: this.editingSessionId,
      idempotencyKey: this.createCommandId(),
      commands: freezeCommands(commands),
      baselineRevision: this.snapshot.document.revision,
      ack,
      sequence: this.sequence++,
      createdAt: Date.now(),
    })
  }

  private async persistAndSend(
    operation: CanvasPendingOperation,
    signal?: AbortSignal,
  ): Promise<CanvasSnapshotDTO> {
    try {
      // 发送前必须完成落盘：无法完成事务时绝不发送，避免产生无法恢复的在途请求。
      await this.store.save(operation)
    } catch (error) {
      this.operations.delete(operation.id)
      throw error
    }

    if (this.blocked) {
      // 前序操作已出现未知结果导致队列阻塞：
      // 本操作已先完成持久化，但绝不得发送越过前序操作；返回清晰可重试错误，不无限挂起 Promise。
      throw new CanvasQueueBlockedError(undefined, this.blockedError)
    }

    return this.send(operation, signal)
  }

  private async send(
    operation: CanvasPendingOperation,
    signal?: AbortSignal,
  ): Promise<CanvasSnapshotDTO> {
    try {
      const patch = await this.apply(
        this.canvasId,
        {
          idempotencyKey: operation.idempotencyKey,
          commands: operation.commands,
        },
        { signal },
      )
      const snapshot = await this.ingestPatch(patch, signal)
      await this.settle(operation)
      return snapshot
    } catch (error) {
      if (isCanvasConflict(error)) {
        // 语义冲突是终态：清除该操作，绝不盲重放；草稿与远端内容都保留，交由 UI 明确解决。
        await this.settle(operation)
        const latest = await this.refetch(this.canvasId, { signal })
        const authoritative = this.replaceSnapshot(latest)
        throw new CanvasCommandConflictError(authoritative, error)
      }
      if (isTerminalClientError(error)) {
        await this.settle(operation)
        throw error
      }
      // 网络 / 服务端瞬时失败：保留冻结操作，标记队列阻塞，等待恢复或重载按原 key/body 幂等重放。
      this.markBlocked(error)
      throw error
    }
  }

  private async runRecovery(): Promise<CanvasCommandRecoveryResult> {
    const result: CanvasCommandRecoveryResult = { replayed: 0, conflicted: 0, failed: 0, ackedDrafts: [] }
    let pending: CanvasPendingOperation[]
    try {
      // storage.list 失败不能伪装空成功，必须标记阻塞并抛出错误。
      pending = await this.store.list()
    } catch (error) {
      this.markBlocked(error)
      throw error
    }

    if (pending.length === 0) {
      this.blocked = false
      this.blockedError = null
      return result
    }

    // 按冻结排序：sequence 升序（相同按 createdAt 升序）
    const sorted = [...pending].sort((a, b) => {
      if (a.sequence !== b.sequence) {
        return a.sequence - b.sequence
      }
      return a.createdAt - b.createdAt
    })

    let hadUnknownFailure = false
    for (const operation of sorted) {
      this.sequence = Math.max(this.sequence, operation.sequence + 1)
      this.operations.set(operation.id, operation)
      try {
        await this.send(operation)
        result.replayed += 1
        result.ackedDrafts.push(...operation.ack)
      } catch (error) {
        if (error instanceof CanvasCommandConflictError) {
          // 409 语义冲突终态：send 内部已 settle(operation)
          result.conflicted += 1
          continue
        }
        if (isTerminalClientError(error)) {
          // 确定 4xx 终态：send 内部已 settle(operation)
          result.failed += 1
          continue
        }
        // 未知结果（网络失败 / 5xx 等）：首个未知结果即停，禁止处理后项！
        result.failed += 1
        this.markBlocked(error)
        hadUnknownFailure = true
        break
      }
    }

    if (!hadUnknownFailure) {
      this.blocked = false
      this.blockedError = null
    }

    return result
  }

  private markBlocked(error: unknown): void {
    this.blocked = true
    this.blockedError = error instanceof Error ? error : new Error(String(error))
  }

  private async settle(operation: CanvasPendingOperation): Promise<void> {
    this.operations.delete(operation.id)
    try {
      await this.store.remove(operation.id)
    } catch {
      // operation store 已通过 onCanvasStorageError 告警；ACK 不因清理失败而回滚内存状态。
    }
  }

  private async ingestPatch(
    patch: CanvasPatchDTO,
    signal?: AbortSignal,
  ): Promise<CanvasSnapshotDTO> {
    // ACK 空 patch 必须 refetch 确保服务器创建的持久 resource ID 与结构不是乐观虚拟 ID
    const isEmptyPatch = patch.nodes.length === 0 && patch.groups.length === 0
    if (isEmptyPatch) {
      const latest = await this.refetch(this.canvasId, { signal })
      return this.replaceSnapshot(latest)
    }

    const relation = compareCanvasRevisions(patch.revision, this.snapshot.document.revision)
    if (relation <= 0) {
      // 重复 / 过期 patch（revision 未前进）直接忽略。
      return this.snapshot
    }
    const contiguous = compareCanvasRevisions(
      this.snapshot.document.revision,
      decrementRevision(patch.revision),
    ) >= 0
    if (!contiguous) {
      // revision 缺口：本地快照落后于 patch 前驱，绝不用增量 patch 假装完整。
      const latest = await this.refetch(this.canvasId, { signal })
      return this.replaceSnapshot(latest)
    }

    const next = applyEntityPatch(this.snapshot, patch)
    if (next) {
      this.adopt(next)
      return next
    }
    // 理论不可达的兜底：无法应用则重取权威快照。
    const latest = await this.refetch(this.canvasId, { signal })
    return this.replaceSnapshot(latest)
  }

  private adopt(snapshot: CanvasSnapshotDTO): void {
    this.snapshot = snapshot
    this.onSnapshot?.(snapshot)
  }
}

function isCanvasConflict(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 409
}

function isTerminalClientError(error: unknown): boolean {
  return error instanceof ApiError
    && typeof error.status === 'number'
    && error.status >= 400
    && error.status < 500
}

/** bigint-safe 的“前驱 revision”，仅用于判断 patch 是否与本地快照连续。 */
function decrementRevision(revision: string): string {
  try {
    const value = BigInt(revision)
    return value > 0n ? (value - 1n).toString() : '0'
  } catch {
    return '0'
  }
}

/** 深拷贝并递归冻结命令体：入队后外部再修改入参也不会影响已冻结 / 已落盘的请求。 */
function freezeCommands(commands: CanvasCommandDTO[]): CanvasCommandDTO[] {
  const clone = typeof structuredClone === 'function'
    ? structuredClone(commands)
    : (JSON.parse(JSON.stringify(commands)) as CanvasCommandDTO[])
  deepFreeze(clone)
  return clone
}

function deepFreeze(value: unknown): void {
  if (!value || typeof value !== 'object' || Object.isFrozen(value)) {
    return
  }
  Object.freeze(value)
  for (const nested of Object.values(value as Record<string, unknown>)) {
    deepFreeze(nested)
  }
}
