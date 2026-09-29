/**
 * Canvas 待确认操作持久化 (IndexedDB)
 *
 * 依据 docs/canvas-project.md §2.4：
 * - “服务端已确认快照 + 待确认操作 + 编辑草稿 = 当前展示”；待确认操作是与草稿分离的独立状态层，
 *   绝不把冻结请求塞进草稿记录的可选字段。
 * - 每个已发送请求的 ID、命令体与编辑基线在发送前冻结并落盘（等待 tx.oncomplete）。
 * - 刷新 / 路由切换 / 重连后按冻结顺序、原 idempotencyKey 与原始命令体重放；幂等去重由服务端保证。
 * - 网络失败保留原待确认操作；语义 409 冲突是终态，绝不盲重放，由 UI 明确解决后生成新 key。
 * - IndexedDB 不可用时绝不宣称已落盘，内存回退仅用于当前标签页会话并必须告警。
 */

import type { CanvasCommandDTO, UUIDString } from '@/shared/api/contracts/studio'
import {
  buildSessionKey,
  CanvasStorageUnavailableError,
  getCurrentUserId,
  notifyCanvasStorageError,
  openCanvasDatabase,
  resolveIdbFactory,
  toStorageError,
  type CanvasLocalStoreOptions,
} from '@/features/canvas/canvas-local-store'
import {
  getEditingSessionId,
  isSessionReloading,
} from '@/features/canvas/canvas-editing-session'

const DB_NAME = 'kkstudio.canvas.operations'
const DB_VERSION = 1
const STORE_NAME = 'operations'
const SESSION_INDEX = 'sessionKey'

/** ACK 时需要精准清除的草稿范围：仅当草稿 generation 与冻结时一致才清除。 */
export type CanvasDraftAckField = 'position' | 'text' | 'function' | 'group'

export interface CanvasDraftAck {
  nodeId: string
  field: CanvasDraftAckField
  generation: number
}

/** 冻结的待确认操作：id、命令体、顺序与编辑基线一经入队即不可变。 */
export interface CanvasPendingOperation {
  /** 主键：`${userId}:${canvasId}:${editingSessionId}:${idempotencyKey}` */
  id: string
  userId: string
  canvasId: string
  editingSessionId: string
  /** 复合检索索引：`${userId}:${canvasId}:${editingSessionId}` */
  sessionKey: string
  idempotencyKey: UUIDString
  /** 冻结的命令批次（含顺序），重放时原样提交 */
  commands: CanvasCommandDTO[]
  /** 冻结时的权威快照 revision，作为该操作的编辑基线 */
  baselineRevision: string
  /** ACK 时需按 operation/generation 精准清除的草稿范围 */
  ack: CanvasDraftAck[]
  /** 单调递增序号：保证重载后严格按原提交顺序重放 */
  sequence: number
  createdAt: number
}

/** 队列使用的持久化抽象，便于在单测中替换为确定性实现。 */
export interface CanvasPendingOperationStore {
  save(operation: CanvasPendingOperation): Promise<void>
  remove(operationId: string): Promise<void>
  list(): Promise<CanvasPendingOperation[]>
}

interface ResolvedScope {
  userId: string
  editingSessionId: string
  sessionKey: string
}

function assertSessionWritable(): void {
  if (isSessionReloading()) {
    throw new CanvasStorageUnavailableError('Session is reloading due to bfcache re-isolation')
  }
}

function resolveScope(canvasId: string, options?: CanvasLocalStoreOptions): ResolvedScope {
  assertSessionWritable()
  const userId = options?.userId ?? getCurrentUserId()
  const editingSessionId = options?.editingSessionId ?? getEditingSessionId()
  return {
    userId,
    editingSessionId,
    sessionKey: buildSessionKey(userId, canvasId, editingSessionId),
  }
}

function upgradeOperations(db: IDBDatabase, transaction: IDBTransaction | null): void {
  if (!db.objectStoreNames.contains(STORE_NAME)) {
    const store = db.createObjectStore(STORE_NAME, { keyPath: 'id' })
    store.createIndex(SESSION_INDEX, 'sessionKey', { unique: false })
    return
  }
  const store = transaction?.objectStore(STORE_NAME)
  if (store && !store.indexNames.contains(SESSION_INDEX)) {
    store.createIndex(SESSION_INDEX, 'sessionKey', { unique: false })
  }
}

let unavailableNotified = false

/** 仅供测试：重置“不可用”告警标记。 */
export function resetCanvasOperationStorage(): void {
  unavailableNotified = false
}

/**
 * 明确宣告 IndexedDB 不可用（只在当前标签页会话内可见，无法真正落盘）。
 * 返回的异常会向上抛给队列，从而阻止“未落盘却照常发送请求”的假成功。
 */
function declareUnavailable(): CanvasStorageUnavailableError {
  const error = new CanvasStorageUnavailableError()
  if (!unavailableNotified) {
    unavailableNotified = true
    notifyCanvasStorageError(error)
  }
  return error
}

/**
 * 冻结并持久化一个待确认操作。严格等待 `tx.oncomplete`，任何 abort / error 都会 reject。
 * IndexedDB 不可用时直接 reject 并告警：绝不 resolve 让调用方在“未落盘”前提下继续发送请求。
 */
export async function saveCanvasOperation(
  operation: CanvasPendingOperation,
  options?: CanvasLocalStoreOptions,
): Promise<void> {
  assertSessionWritable()
  const factory = resolveIdbFactory(options)
  if (!factory) {
    throw declareUnavailable()
  }

  const db = await openCanvasDatabase(factory, DB_NAME, DB_VERSION, upgradeOperations)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const putReq = tx.objectStore(STORE_NAME).put(operation)

      putReq.onerror = () => {
        const error = putReq.error ?? new Error(`Failed to persist operation ${operation.id}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.oncomplete = () => {
        resolve()
      }
      tx.onabort = () => {
        const error = tx.error ?? new Error(`Transaction aborted while persisting operation ${operation.id}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.onerror = () => {
        const error = tx.error ?? new Error(`Transaction failed while persisting operation ${operation.id}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
    } catch (error) {
      const storageError = toStorageError(error)
      notifyCanvasStorageError(storageError)
      reject(storageError)
    }
  })
}

/** 删除一个待确认操作（ACK 或确定冲突终态）。必须等待 `tx.oncomplete`。 */
export async function deleteCanvasOperation(
  operationId: string,
  options?: CanvasLocalStoreOptions,
): Promise<void> {
  assertSessionWritable()
  const factory = resolveIdbFactory(options)
  if (!factory) {
    // 无可落盘内容，删除是幂等空操作；不重复告警。
    return
  }

  const db = await openCanvasDatabase(factory, DB_NAME, DB_VERSION, upgradeOperations)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const deleteReq = tx.objectStore(STORE_NAME).delete(operationId)

      deleteReq.onerror = () => {
        const error = deleteReq.error ?? new Error(`Failed to delete operation ${operationId}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.oncomplete = () => {
        resolve()
      }
      tx.onabort = () => {
        const error = tx.error ?? new Error(`Transaction aborted while deleting operation ${operationId}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.onerror = () => {
        const error = tx.error ?? new Error(`Transaction failed while deleting operation ${operationId}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
    } catch (error) {
      const storageError = toStorageError(error)
      notifyCanvasStorageError(storageError)
      reject(storageError)
    }
  })
}

/** 按原始提交顺序（sequence 升序）加载当前会话 / 画布下的全部待确认操作。 */
export async function loadCanvasOperations(
  canvasId: string,
  options?: CanvasLocalStoreOptions,
): Promise<CanvasPendingOperation[]> {
  const scope = resolveScope(canvasId, options)
  const factory = resolveIdbFactory(options)

  if (!factory) {
    // 不可用意味着没有任何可恢复记录；明确告警，绝不假装“已恢复”。
    throw declareUnavailable()
  }

  const db = await openCanvasDatabase(factory, DB_NAME, DB_VERSION, upgradeOperations)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const index = tx.objectStore(STORE_NAME).index(SESSION_INDEX)
      const request = index.getAll(scope.sessionKey)

      request.onsuccess = () => {
        const records = (request.result ?? []) as CanvasPendingOperation[]
        resolve(records.sort(compareSequence))
      }
      request.onerror = () => {
        const error = request.error ?? new Error('Failed to load pending operations')
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.onerror = () => {
        const error = tx.error ?? new Error('Transaction error while loading pending operations')
        notifyCanvasStorageError(error)
        reject(error)
      }
    } catch (error) {
      const storageError = toStorageError(error)
      notifyCanvasStorageError(storageError)
      reject(storageError)
    }
  })
}

function compareSequence(left: CanvasPendingOperation, right: CanvasPendingOperation): number {
  if (left.sequence !== right.sequence) {
    return left.sequence - right.sequence
  }
  return left.createdAt - right.createdAt
}

/**
 * 构建绑定到特定画布 / 会话的待确认操作存储。
 */
export function createCanvasOperationStore(
  canvasId: UUIDString,
  options?: CanvasLocalStoreOptions,
): CanvasPendingOperationStore {
  const scope = resolveScope(canvasId, options)
  const scopedOptions: CanvasLocalStoreOptions = {
    ...options,
    userId: scope.userId,
    editingSessionId: scope.editingSessionId,
  }

  return {
    save: (operation) => saveCanvasOperation(operation, scopedOptions),
    remove: (operationId) => deleteCanvasOperation(operationId, scopedOptions),
    list: () => loadCanvasOperations(canvasId, scopedOptions),
  }
}

/** 构造一个尚未落盘的新操作记录；id / 顺序 / 基线由队列在入队瞬间确定。 */
export function buildPendingOperation(input: {
  canvasId: UUIDString
  userId: string
  editingSessionId: string
  idempotencyKey: UUIDString
  commands: CanvasCommandDTO[]
  baselineRevision: string
  ack?: CanvasDraftAck[]
  sequence: number
  createdAt: number
}): CanvasPendingOperation {
  const sessionKey = buildSessionKey(input.userId, input.canvasId, input.editingSessionId)
  return {
    id: `${input.userId}:${input.canvasId}:${input.editingSessionId}:${input.idempotencyKey}`,
    userId: input.userId,
    canvasId: input.canvasId,
    editingSessionId: input.editingSessionId,
    sessionKey,
    idempotencyKey: input.idempotencyKey,
    commands: input.commands,
    baselineRevision: input.baselineRevision,
    ack: input.ack ?? [],
    sequence: input.sequence,
    createdAt: input.createdAt,
  }
}
